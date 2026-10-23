//go:build !android

package main

import (
	"errors"
	"io"
)

func configureVPNPlatform(socketName string) (io.Closer, error) {
	return nil, errors.New("VPN platform bridge is only supported on Android")
}
