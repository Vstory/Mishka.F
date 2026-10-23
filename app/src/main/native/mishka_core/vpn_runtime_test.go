package main

import (
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"syscall"
	"testing"
	"time"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/inbound"
	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/component/auth"
	"github.com/metacubex/mihomo/component/process"
	C "github.com/metacubex/mihomo/constant"
	authStore "github.com/metacubex/mihomo/listener/auth"
	"github.com/metacubex/mihomo/tunnel"
)

func TestVPNGatewayIndependentOfFakeIP(t *testing.T) {
	option, err := vpnGatewayOption("172.19.0.1/30,fdfe:dcba:9876::1/126")
	if err != nil {
		t.Fatal(err)
	}
	for _, fakeRange := range []string{"28.0.0.1/8", "198.18.0.1/16"} {
		cfg := parseRuntimeConfig(t, []byte("dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-range: "+fakeRange+"\n"), `{"tun":{"enable":true,"file-descriptor":42}}`)
		option(cfg)
		if got := cfg.General.Tun.Inet4Address; len(got) != 1 || got[0].String() != "172.19.0.1/30" {
			t.Fatalf("fake-ip=%s: IPv4 gateway = %v", fakeRange, got)
		}
		if got := cfg.General.Tun.Inet6Address; len(got) != 1 || got[0].String() != "fdfe:dcba:9876::1/126" {
			t.Fatalf("IPv6 gateway = %v", got)
		}
		if cfg.DNS.FakeIPRange.String() != fakeRange {
			t.Fatalf("gateway changed fake-IP range: %s", cfg.DNS.FakeIPRange)
		}
	}
	option, err = vpnGatewayOption("172.19.0.1/30")
	if err != nil {
		t.Fatal(err)
	}
	cfg := parseRuntimeConfig(t, []byte("rules: []\n"), `{"tun":{"file-descriptor":42}}`)
	option(cfg)
	if len(cfg.General.Tun.Inet6Address) != 0 {
		t.Fatal("IPv4-only Builder retained subscription IPv6 gateway")
	}
}

func TestVPNHTTPListenerIgnoresSubscriptionAuthAndACL(t *testing.T) {
	oldAuth := authStore.Default.Authenticator()
	oldAllowed, oldDisallowed := inbound.AllowedIPs(), inbound.DisAllowedIPs()
	oldProxies, oldProviders, oldMode := tunnel.Proxies(), tunnel.Providers(), tunnel.Mode()
	oldProcessMode, oldStatus := tunnel.FindProcessMode(), tunnel.Status()
	t.Cleanup(func() {
		authStore.Default.SetAuthenticator(oldAuth)
		inbound.SetAllowedIPs(oldAllowed)
		inbound.SetDisAllowedIPs(oldDisallowed)
		tunnel.UpdateProxies(oldProxies, oldProviders)
		tunnel.SetMode(oldMode)
		tunnel.SetFindProcessMode(oldProcessMode)
		switch oldStatus {
		case tunnel.Running:
			tunnel.OnRunning()
		case tunnel.Inner:
			tunnel.OnInnerLoading()
		default:
			tunnel.OnSuspend()
		}
	})
	authStore.Default.SetAuthenticator(auth.NewAuthenticator([]auth.AuthUser{{User: "subscription", Pass: "secret"}}))
	inbound.SetAllowedIPs(nil)
	inbound.SetDisAllowedIPs([]netip.Prefix{netip.MustParsePrefix("127.0.0.0/8")})
	tunnel.UpdateProxies(map[string]C.Proxy{"DIRECT": adapter.NewProxy(outbound.NewDirect())}, nil)
	tunnel.SetMode(tunnel.Direct)
	tunnel.SetFindProcessMode(process.FindProcessOff)
	tunnel.OnRunning()

	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "through runtime tunnel")
	}))
	defer origin.Close()
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 23, 45, 67)})
	if err != nil {
		t.Fatal(err)
	}
	file, err := listener.File()
	listener.Close()
	if err != nil {
		t.Fatal(err)
	}
	fd, err := syscall.Dup(int(file.Fd()))
	file.Close()
	if err != nil {
		t.Fatal(err)
	}
	inherited, err := takeVPNHTTPListener(fd)
	if err != nil {
		t.Fatal(err)
	}
	proxy, err := startVPNHTTPListener(inherited, tunnel.Tunnel)
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	proxyURL, err := url.Parse("http://" + proxy.Address())
	if err != nil {
		t.Fatal(err)
	}
	transport := &http.Transport{Proxy: http.ProxyURL(proxyURL)}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
	response, err := client.Get(origin.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || string(body) != "through runtime tunnel" {
		t.Fatalf("HTTP response = %s %q", response.Status, body)
	}
}
