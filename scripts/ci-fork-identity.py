#!/usr/bin/env python3
"""CI 专用：把 runner 工作区的包名与显示名改写成 fork 身份，仓库源码一行都不落库。

只改四样东西：applicationId、显示名（含 Gradle 产物名前缀 archivesName）、关于页「开放
源代码许可」首位的 fork 条目、以及 applicationId 改动连带的开机自启组件名。端口、TUN 设备
名、iptables chain / fwmark / route table、进程匹配模式一律沿用上游原值 —— 前提是设备上
只装这一版、不与上游同时安装或同时运行。哪天要两版并存，得先把这些全局命名空间重新错开。

包名为什么必须连带改组件名：namespace 保持 top.yukonga.mishka（R 类与类的全限定名都跟它
走），而 BootStartManager 里用 context.packageName 拼组件名 —— 运行时它返回的是
applicationId（fork 值），拼出来的类不存在，开机自启会直接崩。

因此每一项都按「期望的原文 → 改写后」逐条声明，任一项找不到原文即整体失败：上游改了
写法时必须回来同步，不允许静默漏改。改写只作用于 CI 的工作区，仓库源码保持与上游逐字
一致 —— 包名一旦落进仓库，每次 rebase 上游都要撞冲突。

用法：
    python3 scripts/ci-fork-identity.py --app-id top.yukonga.mishka.fork --label Mishka.F
    python3 scripts/ci-fork-identity.py --dry-run
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
KOTLIN_DIR = "app/src/main/kotlin/top/yukonga/mishka"
BUILDSRC_DIR = "build-logic/src/main/kotlin"
PLATFORM = f"{KOTLIN_DIR}/platform"

DEFAULT_APP_ID = "top.yukonga.mishka.fork"
DEFAULT_LABEL = "Mishka.F"


def build_edits(app_id: str, label: str) -> list[tuple[str, str, str, int | None]]:
    """(相对路径, 期望原文, 改写后, 期望出现次数)，次数 None 表示只要求至少一处。"""
    return [
        # ── 身份：包名（applicationId，不动 namespace）──
        (
            "app/build.gradle.kts",
            "applicationId = ProjectConfig.PACKAGE_NAME",
            f'applicationId = "{app_id}"',
            1,
        ),
        # 包名改了但 namespace 没改 → 用 context.packageName 拼出来的组件名会指向不存在的类
        (
            f"{PLATFORM}/BootStartManager.kt",
            '"${context.packageName}.service.BootReceiver"',
            '"top.yukonga.mishka.service.BootReceiver"',
            1,
        ),
        # ── 显示名 ──
        (
            "app/src/main/res/values/strings.xml",
            '<string name="app_name" translatable="false">Mishka</string>',
            f'<string name="app_name" translatable="false">{label}</string>',
            1,
        ),
        # Gradle 侧原始产物名（app/build/outputs/.../Mishka.F-v1.0.0(225)-arm64-v8a-release.apk）：
        #   CI 发布名由 workflow 单独拼，这里只管构建日志与本地产物目录里露出的名字 —— 留 `Mishka`
        #   会让人以为编的是上游包。APP_NAME 全仓仅此一处被引用（app/build.gradle.kts 的 archivesName）。
        (
            f"{BUILDSRC_DIR}/ProjectConfig.kt",
            'const val APP_NAME = "Mishka"',
            f'const val APP_NAME = "{label}"',
            1,
        ),
        # ── 关于页「开放源代码许可」首位放本 fork —— 源码保持与上游逐字一致，条目只在构建时注入 ──
        (
            f"{KOTLIN_DIR}/ui/screen/settings/AboutScreen.kt",
            '"Mishka" to "https://github.com/YuKongA/Mishka",',
            f'"{label}" to "https://github.com/Vstory/Mishka.F",\n'
            + " " * 36
            + '"Mishka" to "https://github.com/YuKongA/Mishka",',
            1,
        ),
    ]


# 改写完成后必须一处不剩的字符串。
FORBIDDEN = [
    "${context.packageName}.",
    'APP_NAME = "Mishka"',
]


def scan_forbidden() -> list[str]:
    hits: list[str] = []
    for root in (KOTLIN_DIR, BUILDSRC_DIR):
        for path in sorted((REPO_ROOT / root).rglob("*.kt")):
            for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                for needle in FORBIDDEN:
                    if needle in line:
                        hits.append(f"{path.relative_to(REPO_ROOT)}:{lineno}: {needle}")
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--app-id", default=DEFAULT_APP_ID)
    parser.add_argument("--label", default=DEFAULT_LABEL)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    # label 不只进 app_name：它还当前缀进 APK 文件名与下载 URL（workflow 的产物名、Gradle 的
    #   archivesName）⇒ 生成名里的非法字符在这里拦下，别等 URL 或命名自检报错。
    if not re.fullmatch(r"[A-Za-z0-9._-]+", args.label):
        print(
            f"::error::--label 只允许 A-Za-z0-9._-（它进 APK 文件名与下载 URL）：{args.label!r}",
            file=sys.stderr,
        )
        return 1

    edits = build_edits(args.app_id, args.label)
    problems: list[str] = []
    for rel, old, new, want in edits:
        path = REPO_ROOT / rel
        if not path.is_file():
            problems.append(f"{rel}: 文件不存在")
            continue
        text = path.read_text(encoding="utf-8")
        got = text.count(old)
        if got == 0:
            problems.append(f"{rel}: 找不到期望原文（上游可能改了写法）: {old}")
        elif want is not None and got != want:
            problems.append(f"{rel}: 期望 {want} 处、实际 {got} 处: {old}")

    if problems:
        print("::error::fork 身份改写的前置校验未通过：", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1

    if args.dry_run:
        for rel, old, new, _ in edits:
            print(f"[dry-run] {rel}\n    - {old}\n    + {new}")
        return 0

    for rel, old, new, _ in edits:
        path = REPO_ROOT / rel
        path.write_text(path.read_text(encoding="utf-8").replace(old, new), encoding="utf-8")
        print(f"  {rel}: {old} → {new}")

    hits = scan_forbidden()
    if hits:
        print("::error::改写后仍有残留：", file=sys.stderr)
        for h in hits:
            print(f"  - {h}", file=sys.stderr)
        return 1

    print(
        f"已改写为 fork 身份：appId={args.app_id} label={args.label}"
        f"（共 {len(edits)} 项，仅工作区，不入库）"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
