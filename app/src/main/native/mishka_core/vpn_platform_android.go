//go:build android

package main

import (
	"io"
	"sync"
	"syscall"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/process"
	C "github.com/metacubex/mihomo/constant"
)

type vpnPlatformRegistration struct {
	client      *vpnPlatformClient
	socketHook  dialer.SocketControl
	packageName process.PackageNameResolver
	once        sync.Once
	err         error
}

func configureVPNPlatform(socketName string) (io.Closer, error) {
	client, err := newVPNPlatformClient(socketName)
	if err != nil {
		return nil, err
	}
	registration := &vpnPlatformRegistration{
		client:      client,
		socketHook:  dialer.DefaultSocketHook,
		packageName: process.DefaultPackageNameResolver,
	}
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		var protectErr error
		if err := conn.Control(func(fd uintptr) {
			protectErr = client.protect(int(fd))
		}); err != nil {
			return err
		}
		// RawConn.Control 的成功只代表 fd 有效，不能吞掉 Android protect 返回的失败。
		return protectErr
	}
	process.DefaultPackageNameResolver = func(metadata *C.Metadata) (string, error) {
		if metadata == nil {
			return "", process.ErrNotFound
		}
		var protocol int32
		switch metadata.NetWork {
		case C.TCP:
			protocol = syscall.IPPROTO_TCP
		case C.UDP:
			protocol = syscall.IPPROTO_UDP
		default:
			return "", process.ErrInvalidNetwork
		}
		// CMFA 分支在初始化 Uid 前查询包名，必须用原始五元组向系统查询。
		uid, name, err := client.queryPackage(protocol, metadata.RawSrcAddr, metadata.RawDstAddr)
		if err != nil {
			return "", err
		}
		if uid < 0 || name == "" {
			return "", process.ErrNotFound
		}
		metadata.Uid = uint32(uid)
		return name, nil
	}
	return registration, nil
}

func (r *vpnPlatformRegistration) Close() error {
	r.once.Do(func() {
		r.err = r.client.Close()
		// runtime 先停止 tunnel 再释放注册；ROOT 从不安装这些全局 callback。
		dialer.DefaultSocketHook = r.socketHook
		process.DefaultPackageNameResolver = r.packageName
	})
	return r.err
}
