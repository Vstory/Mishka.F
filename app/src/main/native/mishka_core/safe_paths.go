package main

import (
	"path/filepath"
	"strings"

	Const "github.com/metacubex/mihomo/constant"
)

// 订阅校验会把 file / http 类 proxy-provider 的 path 在内存里改写成 <workDir>/providers/<文件名> 的绝对路径
// （fetch.go 的 patchProvidersPath），让 ParseRawConfig 读到预取的文件。workDir 有两种来源：
//   - 导入 / 更新订阅：files/mihomo/processing（单例沙箱）；
//   - 编辑器保存 YAML：files/mihomo/imported/<uuid>（FileManagerEditorScreen.saveWithValidation → fetchAndValid(url="")）。
// 两者都在 geodata（mihomo 的 home dir）之外。上游构建带 cmfa tag 时 IsSafePath 直接放行；
// 本定制去掉了 cmfa，于是这两处都会报 "path is not subpath of home directory or SAFE_PATHS"。
// 这里只放行这两种 providers 目录下的文件，其它路径（包括目录本身、..穿越）仍然拒绝。
// 写回磁盘的 config.yaml 仍是相对路径，运行时由 mihomo 按 home dir 解析，不受影响。
func init() {
	Const.ExtraSafePathFunc = isValidationProvidersPath
}

func isValidationProvidersPath(path string) bool {
	home := Const.Path.HomeDir()
	if home == "" {
		return false
	}
	// home 是 files/mihomo/geodata，processing/ 与 imported/ 同在 files/mihomo/ 下
	root := filepath.Dir(home)
	rel, err := filepath.Rel(root, filepath.Clean(path))
	if err != nil || !filepath.IsLocal(rel) {
		return false
	}
	parts := strings.Split(filepath.ToSlash(rel), "/")
	switch {
	case len(parts) == 3 && parts[0] == "processing" && parts[1] == "providers":
		// processing/providers/<file>
		return true
	case len(parts) == 4 && parts[0] == "imported" && parts[2] == "providers":
		// imported/<uuid>/providers/<file>
		return true
	}
	return false
}
