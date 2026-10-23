package main

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strings"

	"github.com/metacubex/mihomo/adapter/inbound"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	authStore "github.com/metacubex/mihomo/listener/auth"
	LC "github.com/metacubex/mihomo/listener/config"
	mihomoHTTP "github.com/metacubex/mihomo/listener/http"
)

func vpnGatewayOption(value string) (hub.Option, error) {
	var inet4, inet6 []netip.Prefix
	for _, address := range strings.Split(value, ",") {
		prefix, err := netip.ParsePrefix(address)
		if err != nil {
			return nil, fmt.Errorf("invalid VPN gateway %q: %w", address, err)
		}
		if prefix.Addr().Is4() {
			inet4 = append(inet4, prefix)
		} else {
			inet6 = append(inet6, prefix)
		}
	}
	if len(inet4) != 1 || len(inet6) > 1 {
		return nil, fmt.Errorf("VPN gateway requires one IPv4 prefix and at most one IPv6 prefix")
	}
	return func(cfg *config.Config) {
		// Builder 的真实地址与 fake-ip 池互不相关，且每次 reload 都必须覆盖。
		cfg.General.Tun.Inet4Address = inet4
		cfg.General.Tun.Inet6Address = inet6
	}, nil
}

func takeVPNHTTPListener(fd int) (net.Listener, error) {
	file := os.NewFile(uintptr(fd), "vpn-http-listener")
	if file == nil {
		return nil, fmt.Errorf("invalid VPN HTTP listener fd %d", fd)
	}
	// FileListener 持有 dup；子进程继承的原 fd 必须在成功和失败时都关闭。
	defer file.Close()
	listener, err := net.FileListener(file)
	if err != nil {
		return nil, fmt.Errorf("adopt VPN HTTP listener: %w", err)
	}
	address, ok := listener.Addr().(*net.TCPAddr)
	if !ok || address.IP.To4() == nil || !address.IP.IsLoopback() {
		listener.Close()
		return nil, fmt.Errorf("VPN HTTP listener must use IPv4 loopback")
	}
	return listener, nil
}

type vpnHTTPListenConfig struct {
	listener net.Listener
}

func (c *vpnHTTPListenConfig) Listen(context.Context, string, string) (net.Listener, error) {
	if c.listener == nil {
		return nil, fmt.Errorf("VPN HTTP listener already consumed")
	}
	listener := c.listener
	c.listener = nil
	return listener, nil
}

func (*vpnHTTPListenConfig) ListenPacket(context.Context, string, string) (net.PacketConn, error) {
	return nil, fmt.Errorf("VPN HTTP listener does not support UDP")
}

func startVPNHTTPListener(listener net.Listener, tunnel C.Tunnel) (*mihomoHTTP.Listener, error) {
	proxy, err := mihomoHTTP.NewWithConfig(
		LC.AuthServer{Enable: true, Listen: listener.Addr().String(), AuthStore: authStore.Nil},
		&vpnHTTPListenConfig{listener: listener}, tunnel,
		// 非默认 inbound 同时绕过订阅 authentication 和 LAN ACL。
		inbound.WithInName("VPN-SYSTEM-HTTP"), inbound.WithSpecialRules(""),
	)
	if err != nil {
		listener.Close()
		return nil, fmt.Errorf("start VPN HTTP listener: %w", err)
	}
	return proxy, nil
}
