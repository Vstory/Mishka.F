//go:build linux && !android

package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"syscall"
	"testing"
	"time"
)

func platformTestClient(t *testing.T, serve func(*net.UnixConn) error) (*vpnPlatformClient, <-chan error) {
	t.Helper()
	name := fmt.Sprintf("mishka-platform-test-%d-%d", os.Getpid(), time.Now().UnixNano())
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: "\x00" + name, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	done := make(chan error, 1)
	go func() {
		conn, err := listener.AcceptUnix()
		if err != nil {
			done <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		done <- serve(conn)
	}()
	client, err := newVPNPlatformClient(name)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	return client, done
}

func TestVPNPlatformProtectPassesDescriptorAndKeepsRejectedConnection(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	defer writer.Close()
	client, done := platformTestClient(t, func(conn *net.UnixConn) error {
		for _, result := range []byte{0, 1} {
			var opcode [1]byte
			oob := make([]byte, syscall.CmsgSpace(4))
			n, oobn, _, _, err := conn.ReadMsgUnix(opcode[:], oob)
			if err != nil {
				return err
			}
			messages, err := syscall.ParseSocketControlMessage(oob[:oobn])
			if err != nil {
				return err
			}
			if len(messages) != 1 {
				return fmt.Errorf("expected one SCM_RIGHTS message, got %d", len(messages))
			}
			fds, err := syscall.ParseUnixRights(&messages[0])
			if err != nil {
				return err
			}
			for _, fd := range fds {
				defer syscall.Close(fd)
			}
			if n != 1 || opcode[0] != 1 || len(fds) != 1 {
				return fmt.Errorf("bad protect frame: n=%d opcode=%d fds=%d", n, opcode[0], len(fds))
			}
			// 对实际收到的 dup 写入，证明传递的是可用 fd，而非仅验证控制消息存在。
			if _, err := syscall.Write(fds[0], []byte{result}); err != nil {
				return err
			}
			if _, err := conn.Write([]byte{result}); err != nil {
				return err
			}
		}
		return nil
	})
	if err := client.protect(int(writer.Fd())); err == nil {
		t.Fatal("rejected protection must fail the dial")
	}
	if err := client.protect(int(writer.Fd())); err != nil {
		t.Fatalf("valid rejection must not desynchronize or close IPC: %v", err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	var received [2]byte
	if _, err := io.ReadFull(reader, received[:]); err != nil {
		t.Fatal(err)
	}
	if received != [2]byte{0, 1} {
		t.Fatalf("wrong received descriptor data: %v", received)
	}
}

func TestVPNPlatformQueryUsesNumericRawEndpoints(t *testing.T) {
	source := &net.TCPAddr{IP: net.ParseIP("fe80::1234"), Port: 4567, Zone: "3"}
	target := &net.TCPAddr{IP: net.ParseIP("2001:db8::2"), Port: 443}
	client, done := platformTestClient(t, func(conn *net.UnixConn) error {
		var header [9]byte
		if _, err := io.ReadFull(conn, header[:]); err != nil {
			return err
		}
		if header[0] != 2 || binary.BigEndian.Uint32(header[1:5]) != syscall.IPPROTO_TCP {
			return fmt.Errorf("bad query header: %v", header)
		}
		for index, expected := range []string{"[fe80::1234%3]:4567", "[2001:db8::2]:443"} {
			length := binary.BigEndian.Uint32(header[5:9])
			if index != 0 {
				if _, err := io.ReadFull(conn, header[5:9]); err != nil {
					return err
				}
				length = binary.BigEndian.Uint32(header[5:9])
			}
			if int(length) != len(expected) {
				return fmt.Errorf("bad address length %d for %q", length, expected)
			}
			bytes := make([]byte, length)
			if _, err := io.ReadFull(conn, bytes); err != nil {
				return err
			}
			if string(bytes) != expected {
				return fmt.Errorf("got raw address %q, want %q", bytes, expected)
			}
		}
		name := "org.example.app"
		var response [8]byte
		binary.BigEndian.PutUint32(response[:4], 10123)
		binary.BigEndian.PutUint32(response[4:], uint32(len(name)))
		if _, err := conn.Write(response[:]); err != nil {
			return err
		}
		_, err := io.WriteString(conn, name)
		return err
	})
	uid, name, err := client.queryPackage(syscall.IPPROTO_TCP, source, target)
	if err != nil {
		t.Fatal(err)
	}
	if uid != 10123 || name != "org.example.app" {
		t.Fatalf("unexpected package query: uid=%d name=%q", uid, name)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestVPNPlatformCloseInterruptsPendingReply(t *testing.T) {
	waiting := make(chan struct{})
	client, done := platformTestClient(t, func(conn *net.UnixConn) error {
		request := make([]byte, 13+len("127.0.0.1:1234")+len("127.0.0.1:443"))
		if _, err := io.ReadFull(conn, request); err != nil {
			return err
		}
		close(waiting)
		_, err := conn.Read(request)
		if err != io.EOF {
			return fmt.Errorf("expected client close, got %v", err)
		}
		return nil
	})
	result := make(chan error, 1)
	go func() {
		_, _, err := client.queryPackage(syscall.IPPROTO_TCP,
			&net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1234},
			&net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 443})
		result <- err
	}()
	<-waiting
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("pending query succeeded after close")
		}
	case <-time.After(time.Second):
		t.Fatal("close did not interrupt pending query")
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
