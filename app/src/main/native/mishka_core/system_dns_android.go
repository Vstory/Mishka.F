//go:build android && (cmfa || mishka)

package main

import "github.com/metacubex/mihomo/dns"

func applySystemDNS(servers []string) error {
	dns.UpdateSystemDNS(servers)
	dns.FlushCacheWithDefaultResolver()
	return nil
}
