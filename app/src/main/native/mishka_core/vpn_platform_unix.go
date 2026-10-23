//go:build android || linux

package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

const vpnPlatformMaxString = 1024

var errVPNPlatformClosed = errors.New("VPN platform bridge is closed")

type vpnPlatformClient struct {
	conn   *net.UnixConn
	mu     sync.Mutex
	closed atomic.Bool
}

func newVPNPlatformClient(socketName string) (*vpnPlatformClient, error) {
	if socketName == "" || len(socketName) > 107 {
		return nil, errors.New("invalid VPN platform socket name")
	}
	for _, char := range socketName {
		if char == 0 {
			return nil, errors.New("invalid VPN platform socket name")
		}
	}
	conn, err := net.DialTimeout("unix", "\x00"+socketName, 5*time.Second)
	if err != nil {
		return nil, fmt.Errorf("connect VPN platform: %w", err)
	}
	return &vpnPlatformClient{conn: conn.(*net.UnixConn)}, nil
}

func (c *vpnPlatformClient) Close() error {
	if c.closed.Swap(true) {
		return nil
	}
	// Close 不等协议 mutex，让停止能立即中断正在等待 Android 回复的调用。
	return c.conn.Close()
}

func (c *vpnPlatformClient) transact(fn func() error) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed.Load() {
		return errVPNPlatformClosed
	}
	if err := c.conn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	if err := fn(); err != nil {
		// 一旦读写失败，帧边界不再可信；不能继续复用而错配下一次 protect 的回复。
		_ = c.Close()
		return err
	}
	return nil
}

func (c *vpnPlatformClient) protect(fd int) error {
	var protected bool
	err := c.transact(func() error {
		rights := syscall.UnixRights(fd)
		n, oobn, err := c.conn.WriteMsgUnix([]byte{1}, rights, nil)
		if err != nil {
			return fmt.Errorf("send VPN protect: %w", err)
		}
		if n != 1 || oobn != len(rights) {
			return io.ErrShortWrite
		}
		var response [1]byte
		if err := c.readFull(response[:]); err != nil {
			return fmt.Errorf("read VPN protect: %w", err)
		}
		if response[0] > 1 {
			return errors.New("invalid VPN protect response")
		}
		protected = response[0] == 1
		return nil
	})
	if err != nil {
		return err
	}
	if !protected {
		return errors.New("VPN service rejected socket protection")
	}
	return nil
}

func (c *vpnPlatformClient) queryPackage(protocol int32, source, target net.Addr) (int32, string, error) {
	src, err := vpnPlatformAddress(source)
	if err != nil {
		return -1, "", err
	}
	dst, err := vpnPlatformAddress(target)
	if err != nil {
		return -1, "", err
	}
	var uid int32
	var name string
	err = c.transact(func() error {
		request := make([]byte, 13+len(src)+len(dst))
		request[0] = 2
		binary.BigEndian.PutUint32(request[1:5], uint32(protocol))
		binary.BigEndian.PutUint32(request[5:9], uint32(len(src)))
		copy(request[9:], src)
		position := 9 + len(src)
		binary.BigEndian.PutUint32(request[position:position+4], uint32(len(dst)))
		copy(request[position+4:], dst)
		if n, err := c.conn.Write(request); err != nil {
			return fmt.Errorf("send VPN package query: %w", err)
		} else if n != len(request) {
			return io.ErrShortWrite
		}
		var header [8]byte
		if err := c.readFull(header[:]); err != nil {
			return fmt.Errorf("read VPN package query: %w", err)
		}
		uid = int32(binary.BigEndian.Uint32(header[:4]))
		length := binary.BigEndian.Uint32(header[4:])
		if length > vpnPlatformMaxString {
			return errors.New("VPN platform package name is too long")
		}
		if length == 0 {
			return nil
		}
		bytes := make([]byte, length)
		if err := c.readFull(bytes); err != nil {
			return fmt.Errorf("read VPN package name: %w", err)
		}
		name = string(bytes)
		return nil
	})
	return uid, name, err
}

// 接收端即使不期待 SCM_RIGHTS 也必须接住并释放，否则异常对端可留下不可见的 dup fd。
func (c *vpnPlatformClient) readFull(bytes []byte) error {
	var ancillary [256]byte
	for len(bytes) > 0 {
		n, oobn, flags, _, err := c.conn.ReadMsgUnix(bytes, ancillary[:])
		if oobn > 0 {
			messages, parseErr := syscall.ParseSocketControlMessage(ancillary[:oobn])
			for _, message := range messages {
				fds, rightsErr := syscall.ParseUnixRights(&message)
				if rightsErr == nil {
					for _, fd := range fds {
						_ = syscall.Close(fd)
					}
				}
			}
			if parseErr != nil {
				return parseErr
			}
			return errors.New("unexpected VPN platform ancillary data")
		}
		if flags&syscall.MSG_CTRUNC != 0 {
			return errors.New("truncated VPN platform ancillary data")
		}
		if err != nil {
			return err
		}
		if n == 0 {
			return io.EOF
		}
		bytes = bytes[n:]
	}
	return nil
}

func vpnPlatformAddress(address net.Addr) (string, error) {
	if address == nil {
		return "", errors.New("VPN package query has no raw socket address")
	}
	endpoint, err := netip.ParseAddrPort(address.String())
	if err != nil {
		return "", fmt.Errorf("invalid VPN package query address: %w", err)
	}
	ip := endpoint.Addr()
	if zone := ip.Zone(); zone != "" {
		if _, err := strconv.ParseUint(zone, 10, 32); err != nil {
			iface, err := net.InterfaceByName(zone)
			if err != nil {
				return "", fmt.Errorf("resolve VPN socket scope: %w", err)
			}
			ip = ip.WithZone(strconv.Itoa(iface.Index))
		}
	}
	return netip.AddrPortFrom(ip, endpoint.Port()).String(), nil
}
