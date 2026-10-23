//go:build linux || android

package main

import (
	"errors"
	"fmt"
	"net"
	"syscall"

	"golang.org/x/sys/unix"
)

func rootDNSSocketControl(network, address string, raw syscall.RawConn) error {
	var socketErr error
	err := raw.Control(func(fd uintptr) {
		level, option := unix.SOL_IP, unix.IP_TRANSPARENT
		if network == "tcp6" || network == "udp6" {
			level, option = unix.SOL_IPV6, unix.IPV6_TRANSPARENT
		}
		socketErr = unix.SetsockoptInt(int(fd), level, option, 1)
	})
	if err != nil {
		return err
	}
	return socketErr
}

func listenRootDNS() ([]rootDNSSockets, error) {
	lc := &net.ListenConfig{Control: rootDNSSocketControl}
	ipv4, err := bindRootDNSFamily(lc, "4", "0.0.0.0:53")
	if err != nil {
		return nil, err
	}
	sockets := []rootDNSSockets{ipv4}
	complete := false
	defer func() {
		if !complete {
			for _, socket := range sockets {
				socket.udp.Close()
				socket.tcp.Close()
			}
		}
	}()

	// 只在内核确实缺少 IPv6 协议族时退回 IPv4；权限、端口冲突等错误必须终止启动。
	fd, err := unix.Socket(unix.AF_INET6, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if errors.Is(err, unix.EAFNOSUPPORT) || errors.Is(err, unix.EPROTONOSUPPORT) {
		complete = true
		return sockets, nil
	}
	if err != nil {
		return nil, fmt.Errorf("probe ROOT DNS IPv6 support: %w", err)
	}
	unix.Close(fd)
	ipv6, err := bindRootDNSFamily(lc, "6", "[::]:53")
	if err != nil {
		return nil, err
	}
	sockets = append(sockets, ipv6)
	complete = true
	return sockets, nil
}
