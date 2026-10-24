package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/config"
	"go.yaml.in/yaml/v3"

	"mishka_core/overrides"
)

// age 订阅在磁盘上保持密文，变换只能作用于解密后的明文；没有 age 头的内容原样返回。
func decryptConfig(configBytes []byte, ageSecretKey string) ([]byte, error) {
	var keys []string
	if key := strings.TrimSpace(ageSecretKey); key != "" {
		keys = append(keys, key)
	}
	plain, err := age.DecryptBytes(configBytes, keys...)
	if err != nil {
		return nil, fmt.Errorf("decrypt config: %w", err)
	}
	return plain, nil
}

func applyTransformFile(configBytes []byte, transformPath, ageSecretKey string) ([]byte, error) {
	transform, err := overrides.LoadTransform(transformPath)
	if err != nil {
		return nil, err
	}
	plain, err := decryptConfig(configBytes, ageSecretKey)
	if err != nil {
		return nil, err
	}
	return transform.Apply(plain)
}

// transformPath 为空时只解密，不套变换。
func readTransformedConfig(workDir, transformPath, ageSecretKey string) ([]byte, error) {
	raw, err := os.ReadFile(filepath.Join(workDir, "config.yaml"))
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	if transformPath == "" {
		return decryptConfig(raw, ageSecretKey)
	}
	return applyTransformFile(raw, transformPath, ageSecretKey)
}

type transformCheck struct {
	Valid bool `json:"valid"`
}

type runtimeConfigValues struct {
	ExternalController *string         `json:"externalController"`
	Secret             *string         `json:"secret"`
	TunDevice          *string         `json:"tunDevice"`
	TproxyPort         *int            `json:"tproxyPort,omitempty"`
	HasTun             bool            `json:"hasTun"`
	Tun                json.RawMessage `json:"tun,omitempty"`
	Listeners          json.RawMessage `json:"listeners,omitempty"`
	ProxyProviders     json.RawMessage `json:"proxyProviders,omitempty"`
}

func extractRuntimeConfigValues(configBytes []byte) (runtimeConfigValues, error) {
	var root map[string]interface{}
	if err := yaml.Unmarshal(configBytes, &root); err != nil {
		return runtimeConfigValues{}, err
	}
	values := runtimeConfigValues{}
	if raw, ok := root["external-controller"]; ok {
		value := yamlScalarString(raw)
		values.ExternalController = &value
	}
	if raw, ok := root["secret"]; ok {
		value := yamlScalarString(raw)
		values.Secret = &value
	}
	if rawTun, ok := root["tun"].(map[string]interface{}); ok {
		values.HasTun = true
		encoded, err := json.Marshal(rawTun)
		if err != nil {
			return runtimeConfigValues{}, fmt.Errorf("marshal tun: %w", err)
		}
		values.Tun = encoded
		if rawDevice, exists := rawTun["device"]; exists {
			value := yamlScalarString(rawDevice)
			values.TunDevice = &value
		}
	}
	if raw, ok := root["tproxy-port"]; ok {
		if port, err := strconv.Atoi(strings.TrimSpace(yamlScalarString(raw))); err == nil {
			values.TproxyPort = &port
		}
	}
	if raw, ok := root["listeners"]; ok && raw != nil {
		encoded, err := json.Marshal(raw)
		if err != nil {
			return runtimeConfigValues{}, fmt.Errorf("marshal listeners: %w", err)
		}
		values.Listeners = encoded
	}
	if raw, ok := root["proxy-providers"]; ok && raw != nil {
		encoded, err := json.Marshal(raw)
		if err != nil {
			return runtimeConfigValues{}, fmt.Errorf("marshal proxy providers: %w", err)
		}
		values.ProxyProviders = encoded
	}
	return values, nil
}

func yamlScalarString(value interface{}) string {
	if value == nil {
		return ""
	}
	if text, ok := value.(string); ok {
		return text
	}
	return fmt.Sprint(value)
}

// 解密并应用与 mihomo 启动时相同的订阅变换后，读取服务启动前需要的配置值、TPROXY 端口与 listener 列表。
//
//export mishkaReadRuntimeConfigValues
func mishkaReadRuntimeConfigValues(cWorkDir, cTransform, cKey *C.char) *C.char {
	return guardString(func() string {
		workDir := C.GoString(cWorkDir)
		transformPath := C.GoString(cTransform)
		key := C.GoString(cKey)
		age.SetGlobalSecretKeys(key)
		defer age.SetGlobalSecretKeys()
		configBytes, err := readTransformedConfig(workDir, transformPath, key)
		if err != nil {
			return "error: " + err.Error()
		}
		values, err := extractRuntimeConfigValues(configBytes)
		if err != nil {
			return "error: inspect config: " + err.Error()
		}
		data, err := json.Marshal(values)
		if err != nil {
			return "error: marshal config values: " + err.Error()
		}
		return string(data)
	})
}

// 与运行时同一套变换与解析流程：保存改动前先在应用内跑一遍，失败时不去重启代理。
//
//export mishkaValidateTransform
func mishkaValidateTransform(cWorkDir, cTransform, cKey *C.char) *C.char {
	return guardString(func() string {
		workDir := C.GoString(cWorkDir)
		// provider 校验同样需要订阅密钥，调用方的 processLock 保证全局密钥串行使用。
		age.SetGlobalSecretKeys(C.GoString(cKey))
		defer age.SetGlobalSecretKeys()
		out, err := readTransformedConfig(workDir, C.GoString(cTransform), C.GoString(cKey))
		if err != nil {
			return "error: " + err.Error()
		}
		rawCfg, err := config.UnmarshalRawConfig(out)
		if err != nil {
			return "error: unmarshal config: " + err.Error()
		}
		patchProvidersPath(rawCfg, workDir)
		cfg, err := config.ParseRawConfig(rawCfg)
		if err != nil {
			return "error: validate config: " + err.Error()
		}
		destroyProviders(cfg)
		data, _ := json.Marshal(transformCheck{Valid: true})
		return string(data)
	})
}
