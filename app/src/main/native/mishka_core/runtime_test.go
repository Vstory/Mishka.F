package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/config"
	Const "github.com/metacubex/mihomo/constant"
)

func parseRuntimeConfig(t *testing.T, data []byte, override string) *config.Config {
	t.Helper()
	previousOverride := config.OverrideJSONPath
	t.Cleanup(func() { config.OverrideJSONPath = previousOverride })
	config.OverrideJSONPath = ""
	if override != "" {
		path := filepath.Join(t.TempDir(), "override.json")
		if err := os.WriteFile(path, []byte(override), 0600); err != nil {
			t.Fatal(err)
		}
		config.OverrideJSONPath = path
	}
	cfg, err := config.Parse(data)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { destroyProviders(cfg) })
	return cfg
}

func TestRuntimeTunStack(t *testing.T) {
	tests := []struct {
		name         string
		subscription string
		override     string
		want         Const.TUNStack
	}{
		{"no stack set", "rules: []\n", `{ "tun": { "enable": true } }`, Const.TunMips},
		{"VPN default", "rules: []\n", `{ "tun": { "enable": true, "file-descriptor": 42, "mtu": 9000, "auto-route": false } }`, Const.TunMips},
		{"subscription stack", "tun:\n  stack: system\n", `{ "tun": { "enable": true } }`, Const.TunSystem},
		{"user stack", "rules: []\n", `{ "tun": { "enable": true, "stack": "gvisor" } }`, Const.TunGvisor},
		{"both stacks", "tun:\n  stack: system\n", `{ "tun": { "enable": true, "stack": "gvisor" } }`, Const.TunGvisor},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := parseRuntimeConfig(t, []byte(tt.subscription), tt.override).General.Tun.Stack
			if got != tt.want {
				t.Fatalf("TUN stack = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestRuntimeTunStackWithEncryptedSubscription(t *testing.T) {
	secretKey, publicKey, err := age.GenX25519KeyPair()
	if err != nil {
		t.Fatal(err)
	}
	data, err := age.EncryptBytes([]byte("tun:\n  stack: mixed\n"), publicKey)
	if err != nil {
		t.Fatal(err)
	}
	age.SetGlobalSecretKeys(secretKey)
	t.Cleanup(func() { age.SetGlobalSecretKeys() })
	got := parseRuntimeConfig(t, data, `{ "tun": { "enable": true } }`).General.Tun.Stack
	if got != Const.TunMixed {
		t.Fatal("encrypted subscription's explicit stack was ignored")
	}
}
func TestRuntimeTunCongestionController(t *testing.T) {
	for _, fd := range []int{0, 42} {
		t.Run(fmt.Sprintf("fd=%d", fd), func(t *testing.T) {
			subscription := []byte("tun:\n  enable: true\n  congestion-controller: bbr\n")
			override := fmt.Sprintf(`{ "tun": { "file-descriptor": %d, "mtu": 9000, "auto-route": false } }`, fd)
			got := parseRuntimeConfig(t, subscription, override).General.Tun.CongestionController
			want := "bbr"
			if fd > 0 {
				want = ""
			}
			if got != want {
				t.Fatalf("TUN congestion controller = %q, want %q", got, want)
			}
		})
	}
}

func TestExtractRuntimeConfigValues(t *testing.T) {
	got, err := extractRuntimeConfigValues([]byte(`external-controller: 0.0.0.0:9090
secret: "s#ecret" # trailing comment
tun:
  enable: true
  device: mihomo0
`))
	if err != nil {
		t.Fatal(err)
	}
	if got.ExternalController == nil || *got.ExternalController != "0.0.0.0:9090" {
		t.Fatalf("external controller = %v, want 0.0.0.0:9090", got.ExternalController)
	}
	if got.Secret == nil || *got.Secret != "s#ecret" {
		t.Fatalf("secret = %v, want s#ecret", got.Secret)
	}
	if got.TunDevice == nil || *got.TunDevice != "mihomo0" {
		t.Fatalf("tun device = %v, want mihomo0", got.TunDevice)
	}
	if !got.HasTun {
		t.Fatal("present tun map was reported as missing")
	}
	var tun map[string]interface{}
	if err := json.Unmarshal(got.Tun, &tun); err != nil {
		t.Fatalf("decode tun mapping: %v", err)
	}
	if tun["enable"] != true || tun["device"] != "mihomo0" {
		t.Fatalf("unexpected tun mapping: %#v", tun)
	}
}

func TestExtractRuntimeConfigValuesRetainsAllTunParameters(t *testing.T) {
	got, err := extractRuntimeConfigValues([]byte(`tun:
  enable: false
  stack: mixed
  mtu: 1420
  gso: false
  gso-max-size: 32768
  dns-hijack:
    - any:53
  route-exclude-address:
    - 192.168.0.0/16
`))
	if err != nil {
		t.Fatal(err)
	}
	var tun map[string]interface{}
	if err := json.Unmarshal(got.Tun, &tun); err != nil {
		t.Fatalf("decode tun mapping: %v", err)
	}
	if tun["enable"] != false || tun["stack"] != "mixed" || tun["mtu"] != float64(1420) ||
		tun["gso"] != false || tun["gso-max-size"] != float64(32768) {
		t.Fatalf("tun parameters were not retained: %#v", tun)
	}
	if addresses, ok := tun["route-exclude-address"].([]interface{}); !ok || len(addresses) != 1 || addresses[0] != "192.168.0.0/16" {
		t.Fatalf("tun route exclusions were not retained: %#v", tun["route-exclude-address"])
	}
}

func TestExtractRuntimeConfigValuesIncludesRootModeFields(t *testing.T) {
	got, err := extractRuntimeConfigValues([]byte(`tproxy-port: 7917
listeners:
  - name: keep-me
    type: http
    port: 8080
  - name: ebpf-in
    type: ebpf
    local:
      enable: true
`))
	if err != nil {
		t.Fatal(err)
	}
	if got.TproxyPort == nil || *got.TproxyPort != 7917 {
		t.Fatalf("tproxy port = %v, want 7917", got.TproxyPort)
	}
	if got.HasTun {
		t.Fatal("missing tun section was reported as present")
	}
	var listeners []map[string]interface{}
	if err := json.Unmarshal(got.Listeners, &listeners); err != nil {
		t.Fatalf("decode listeners: %v", err)
	}
	if len(listeners) != 2 || listeners[0]["name"] != "keep-me" || listeners[1]["type"] != "ebpf" {
		t.Fatalf("unexpected listener list: %#v", listeners)
	}
}

func TestExtractRuntimeConfigValuesIncludesProxyProviders(t *testing.T) {
	got, err := extractRuntimeConfigValues([]byte(`proxy-providers:
  "local nodes":
    type: file
    path: ./proxies/local.yaml
  remote nodes:
    type: http
    path: ./proxies/remote.yaml
`))
	if err != nil {
		t.Fatal(err)
	}
	var providers map[string]map[string]interface{}
	if err := json.Unmarshal(got.ProxyProviders, &providers); err != nil {
		t.Fatalf("decode proxy providers: %v", err)
	}
	if providers["local nodes"]["type"] != "file" || providers["local nodes"]["path"] != "./proxies/local.yaml" {
		t.Fatalf("local file provider source was not retained: %#v", providers["local nodes"])
	}
	if providers["remote nodes"]["type"] != "http" || providers["remote nodes"]["path"] != "./proxies/remote.yaml" {
		t.Fatalf("remote provider source was not retained: %#v", providers["remote nodes"])
	}
}

func TestExtractRuntimeConfigValuesPreservesExplicitEmptySecret(t *testing.T) {
	got, err := extractRuntimeConfigValues([]byte("secret: ''\n"))
	if err != nil {
		t.Fatal(err)
	}
	if got.Secret == nil || *got.Secret != "" {
		t.Fatalf("secret = %v, want an explicitly empty value", got.Secret)
	}
	if got.ExternalController != nil {
		t.Fatalf("missing external controller = %v, want nil", got.ExternalController)
	}
}

func TestReadRuntimeConfigValuesAfterYAMLTransform(t *testing.T) {
	workDir := t.TempDir()
	config := []byte("external-controller: 10.0.0.1:9090\nsecret: profile-secret\ntun:\n  device: original-tun\n  mtu: 1400\n  gso: false\n")
	if err := os.WriteFile(filepath.Join(workDir, "config.yaml"), config, 0600); err != nil {
		t.Fatal(err)
	}
	overridePath := filepath.Join(workDir, "runtime-override.yaml")
	override := []byte("secret: transformed-secret\ntun:\n  device: transformed-tun\n  stack: gvisor\n  mtu: 9000\n  gso: true\nproxy-providers:\n  local-from-transform:\n    type: file\n    path: ./proxies/transformed.yaml\n")
	if err := os.WriteFile(overridePath, override, 0600); err != nil {
		t.Fatal(err)
	}
	transformPath := filepath.Join(workDir, "transform.json")
	transform := []byte(`{"overrides":[{"name":"runtime-test","format":"yaml","path":"` + overridePath + `"}]}`)
	if err := os.WriteFile(transformPath, transform, 0600); err != nil {
		t.Fatal(err)
	}
	transformed, err := readTransformedConfig(workDir, transformPath, "")
	if err != nil {
		t.Fatal(err)
	}
	got, err := extractRuntimeConfigValues(transformed)
	if err != nil {
		t.Fatal(err)
	}
	if got.ExternalController == nil || *got.ExternalController != "10.0.0.1:9090" ||
		got.Secret == nil || *got.Secret != "transformed-secret" ||
		got.TunDevice == nil || *got.TunDevice != "transformed-tun" {
		t.Fatalf("unexpected transformed profile values: %+v", got)
	}
	var tun map[string]interface{}
	if err := json.Unmarshal(got.Tun, &tun); err != nil {
		t.Fatalf("decode transformed tun mapping: %v", err)
	}
	if tun["stack"] != "gvisor" || tun["mtu"] != float64(9000) || tun["gso"] != true {
		t.Fatalf("unexpected transformed tun values: %#v", tun)
	}
	var providers map[string]map[string]interface{}
	if err := json.Unmarshal(got.ProxyProviders, &providers); err != nil {
		t.Fatalf("decode transformed proxy providers: %v", err)
	}
	if providers["local-from-transform"]["type"] != "file" || providers["local-from-transform"]["path"] != "./proxies/transformed.yaml" {
		t.Fatalf("transformed proxy provider source was not retained: %#v", providers["local-from-transform"])
	}
}

func TestReadRuntimeConfigValuesFromEncryptedProfile(t *testing.T) {
	secretKey, publicKey, err := age.GenX25519KeyPair()
	if err != nil {
		t.Fatal(err)
	}
	plain := []byte("external-controller: 192.168.1.20:9090\nsecret: profile-secret\ntun:\n  device: tun-from-profile\nproxy-providers:\n  local-encrypted:\n    type: file\n    path: ./proxies/encrypted.yaml\n")
	ciphertext, err := age.EncryptBytes(plain, publicKey)
	if err != nil {
		t.Fatal(err)
	}
	workDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(workDir, "config.yaml"), ciphertext, 0600); err != nil {
		t.Fatal(err)
	}
	decrypted, err := readTransformedConfig(workDir, "", secretKey)
	if err != nil {
		t.Fatal(err)
	}
	got, err := extractRuntimeConfigValues(decrypted)
	if err != nil {
		t.Fatal(err)
	}
	if got.ExternalController == nil || *got.ExternalController != "192.168.1.20:9090" ||
		got.Secret == nil || *got.Secret != "profile-secret" ||
		got.TunDevice == nil || *got.TunDevice != "tun-from-profile" {
		t.Fatalf("unexpected decrypted profile values: %+v", got)
	}
	var providers map[string]map[string]interface{}
	if err := json.Unmarshal(got.ProxyProviders, &providers); err != nil {
		t.Fatalf("decode encrypted proxy providers: %v", err)
	}
	if providers["local-encrypted"]["type"] != "file" || providers["local-encrypted"]["path"] != "./proxies/encrypted.yaml" {
		t.Fatalf("encrypted proxy provider source was not retained: %#v", providers["local-encrypted"])
	}
}
