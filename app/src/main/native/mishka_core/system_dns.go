package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/netip"
	"slices"
	"strings"
	"sync"

	"github.com/metacubex/chi"
	"github.com/metacubex/chi/render"
	"github.com/metacubex/http"
	"github.com/metacubex/mihomo/hub/route"
)

var errSystemDNSUnsupported = errors.New("system DNS updates require Android with the cmfa build tag")

var systemDNSState struct {
	sync.Mutex
	initialized bool
	servers     []string
}

var registerSystemDNSOnce sync.Once

// DNS 服务器只能是数字地址；此处解析主机名会反过来依赖尚未初始化的系统 DNS。
func parseSystemDNS(servers []string) ([]string, error) {
	parsed := make([]string, len(servers))
	for i, server := range servers {
		addr, err := netip.ParseAddrPort(server)
		if err != nil || addr.Port() == 0 {
			return nil, fmt.Errorf("invalid system DNS socket address %q", server)
		}
		parsed[i] = addr.String()
	}
	return parsed, nil
}

func updateSystemDNS(servers []string) error {
	parsed, err := parseSystemDNS(servers)
	if err != nil {
		return err
	}

	// 串行发布，校验失败保留旧状态。
	systemDNSState.Lock()
	defer systemDNSState.Unlock()
	if systemDNSState.initialized && slices.Equal(systemDNSState.servers, parsed) {
		return nil
	}
	if err := applySystemDNS(parsed); err != nil {
		return err
	}
	systemDNSState.servers = parsed
	systemDNSState.initialized = true
	return nil
}

func updateSystemDNSCommaList(value string) error {
	if value == "" {
		return updateSystemDNS(nil)
	}
	return updateSystemDNS(strings.Split(value, ","))
}

func registerSystemDNSRoute() {
	registerSystemDNSOnce.Do(func() {
		// 外部路由挂在已有 controller 鉴权内部，不能另开无鉴权入口。
		route.Register(func(r chi.Router) {
			r.Put("/dns/system", putSystemDNS)
		})
	})
}

func putSystemDNS(w http.ResponseWriter, r *http.Request) {
	var servers []string
	decoder := json.NewDecoder(r.Body)
	err := decoder.Decode(&servers)
	if err == nil && servers == nil {
		err = errors.New("system DNS body must be a JSON array of socket addresses")
	}
	if err == nil {
		var extra any
		if trailing := decoder.Decode(&extra); trailing != io.EOF {
			err = errors.New("system DNS body must contain exactly one JSON array")
		}
	}
	if err == nil {
		err = updateSystemDNS(servers)
	}
	if err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, errSystemDNSUnsupported) {
			status = http.StatusNotImplemented
		}
		render.Status(r, status)
		render.JSON(w, r, render.M{"message": err.Error()})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
