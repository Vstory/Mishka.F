#!/usr/bin/env python3
"""CI 专用：把 runner 工作区改写成 fork 身份，仓库里的源码一行都不落库。

fork 包与上游原版要能**同时安装、同时运行**，两边用到的一切全局命名空间都必须错开 ——
包名、显示名（含 Gradle 产物名前缀 archivesName）、TUN 设备名、iptables chain 名与
xt_comment 标签、fwmark / route table / ip rule 优先级、TPROXY 与 DNS 端口、按名字匹配
进程的 pgrep/pkill 模式。

漏改任何一项都**不会**编译失败，只会在两个包同时跑时静默互相拆台。最典型的一条：
`RootTproxyApplier.teardown()` / `RootTetherHijacker.teardown()` 按 chain 名与标签清规则，
名字撞上就会把上游实例的规则一并清掉 —— 上游看着还在跑，流量却已裸奔。

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
SERVICE = f"{KOTLIN_DIR}/service"
PLATFORM = f"{KOTLIN_DIR}/platform"

DEFAULT_APP_ID = "top.yukonga.mishka.fork"
DEFAULT_LABEL = "Mishka.F"

# fork 专属取值。全部与上游错开：上游原版照旧用 "Mishka" / 0x01000000 / 2024 / 7999 /
# 7895 / 1053 / 7890 / 9090 / 2022 / 9000。
TUN_DEVICE = "MishkaF"
NS = "mishkafork"  # 内核态命名空间：chain 名与 xt_comment 标签共用的前缀
MARK = "0x02000000"
FWMARK_TABLE = "2025"
PRIORITY_FWMARK = "7997"
TETHER_PRIORITIES = ("8010", "8011", "8012", "8013")
TPROXY_PORT = "7896"
DNS_PORT = "1054"
MIXED_PORT = "7891"
EXT_CTL = "127.0.0.1:9091"
ROOT_TUN_TABLE = "2023"
ROOT_TUN_RULE_INDEX = "9010"


def build_edits(app_id: str, label: str) -> list[tuple[str, str, str, int | None]]:
    """(相对路径, 期望原文, 改写后, 期望出现次数)，次数 None 表示只要求至少一处。"""
    return [
        # ── 身份：包名（applicationId，不动 namespace）、开机自启组件名、显示名 ──
        (
            "app/build.gradle.kts",
            "applicationId = ProjectConfig.PACKAGE_NAME",
            f'applicationId = "{app_id}"',
            1,
        ),
        (
            f"{PLATFORM}/BootStartManager.kt",
            '"${context.packageName}.service.BootReceiver"',
            '"top.yukonga.mishka.service.BootReceiver"',
            1,
        ),
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
        # ── TUN 设备名：root TUN 模式两个实例抢同名接口，清理时 ip link delete 会删掉对方的 ──
        (
            f"{SERVICE}/RuntimeOverrideBuilder.kt",
            f'internal const val DEFAULT_TUN_DEVICE = "Mishka"',
            f'internal const val DEFAULT_TUN_DEVICE = "{TUN_DEVICE}"',
            1,
        ),
        (
            f"{SERVICE}/MihomoRunner.kt",
            'StorageKeys.ROOT_TUN_DEVICE, "Mishka")',
            f'StorageKeys.ROOT_TUN_DEVICE, "{TUN_DEVICE}")',
            1,
        ),
        (
            f"{SERVICE}/RootHelper.kt",
            'tunDevice: String = "Mishka"',
            f'tunDevice: String = "{TUN_DEVICE}"',
            2,
        ),
        (
            f"{KOTLIN_DIR}/ui/screen/settings/RootSettingsScreen.kt",
            'private const val DEFAULT_TUN_DEVICE = "Mishka"',
            f'private const val DEFAULT_TUN_DEVICE = "{TUN_DEVICE}"',
            1,
        ),
        # ── 进程匹配：pgrep/pkill 按 cmdline 匹配，只按 .so 名会连对方实例一起杀 ──
        #    包名出现在二进制路径里（/data/app/~~x/<pkg>-y/lib/arm64/libmihomo_runner.so）
        (
            f"{SERVICE}/RootHelper.kt",
            "-f libmihomo_runner.so",
            f"-f '{app_id}.*libmihomo_runner.so'",
            9,
        ),
        # ── TPROXY 内核态（RootTetherHijacker：tether 共享/热点劫持） ──
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "private const val PRIORITY_V4 = 8000",
            f"private const val PRIORITY_V4 = {TETHER_PRIORITIES[0]}",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "private const val PRIORITY_V6 = 8001",
            f"private const val PRIORITY_V6 = {TETHER_PRIORITIES[1]}",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "private const val PRIORITY_RETURN_V4 = 8002",
            f"private const val PRIORITY_RETURN_V4 = {TETHER_PRIORITIES[2]}",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "private const val PRIORITY_RETURN_V6 = 8003",
            f"private const val PRIORITY_RETURN_V6 = {TETHER_PRIORITIES[3]}",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "internal const val TPROXY_PORT = 7895",
            f"internal const val TPROXY_PORT = {TPROXY_PORT}",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "private const val TPROXY_MARK = 0x01000000",
            f"private const val TPROXY_MARK = {MARK}",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "private const val TPROXY_MASK = 0x01000000",
            f"private const val TPROXY_MASK = {MARK}",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "private const val FWMARK_TABLE = 2024",
            f"private const val FWMARK_TABLE = {FWMARK_TABLE}",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "private const val PRIORITY_FWMARK = 7999",
            f"private const val PRIORITY_FWMARK = {PRIORITY_FWMARK}",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            'private const val CHAIN_NAME = "mishka_tether"',
            f'private const val CHAIN_NAME = "{NS}_tether"',
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            'private const val CHAIN_DIVERT_NAME = "mishka_tether_divert"',
            f'private const val CHAIN_DIVERT_NAME = "{NS}_tether_divert"',
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            'internal const val COMMENT_TAG_PREFIX = "mishka:tether:"',
            f'internal const val COMMENT_TAG_PREFIX = "{NS}:tether:"',
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            'val probeName = "mishka_probe_',
            f'val probeName = "{NS}_probe_',
            1,
        ),
        # ── TPROXY 内核态（RootTproxyApplier：全局 tproxy 劫持） ──
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "internal const val MARK = 0x01000000",
            f"internal const val MARK = {MARK}",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "internal const val MASK = 0x01000000",
            f"internal const val MASK = {MARK}",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "internal const val TABLE = 2024",
            f"internal const val TABLE = {FWMARK_TABLE}",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "internal const val PRIORITY = 7999",
            f"internal const val PRIORITY = {PRIORITY_FWMARK}",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "internal const val DNS_PORT = 1053",
            f"internal const val DNS_PORT = {DNS_PORT}",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "internal const val TPROXY_PORT = RootTetherHijacker.TPROXY_PORT  // 7895",
            f"internal const val TPROXY_PORT = RootTetherHijacker.TPROXY_PORT  // {TPROXY_PORT}",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            'private const val CHAIN_PRE = "mishka_tproxy_pre"',
            f'private const val CHAIN_PRE = "{NS}_tproxy_pre"',
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            'private const val CHAIN_OUT = "mishka_tproxy_out"',
            f'private const val CHAIN_OUT = "{NS}_tproxy_out"',
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            'private const val CHAIN_DIVERT = "mishka_tproxy_divert"',
            f'private const val CHAIN_DIVERT = "{NS}_tproxy_divert"',
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            'private const val CHAIN_DNS_PRE = "mishka_dns_pre"',
            f'private const val CHAIN_DNS_PRE = "{NS}_dns_pre"',
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            'private const val CHAIN_DNS_OUT = "mishka_dns_out"',
            f'private const val CHAIN_DNS_OUT = "{NS}_dns_out"',
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            'internal const val COMMENT_TAG_PREFIX = "mishka:tproxy:"',
            f'internal const val COMMENT_TAG_PREFIX = "{NS}:tproxy:"',
            1,
        ),
        # ── ROOT TUN 路由常量（sing-tun 注入值） ──
        (
            f"{SERVICE}/RuntimeOverrideBuilder.kt",
            "internal const val ROOT_TUN_TABLE = 2022",
            f"internal const val ROOT_TUN_TABLE = {ROOT_TUN_TABLE}",
            1,
        ),
        (
            f"{SERVICE}/RuntimeOverrideBuilder.kt",
            "internal const val ROOT_TUN_RULE_INDEX = 9000",
            f"internal const val ROOT_TUN_RULE_INDEX = {ROOT_TUN_RULE_INDEX}",
            1,
        ),
        # ── 客户端/兜底端口：两个核心同时起时抢同一端口会直接绑不上 ──
        (
            f"{SERVICE}/RuntimeOverrideBuilder.kt",
            "internal const val DEFAULT_MIXED_PORT = 7890",
            f"internal const val DEFAULT_MIXED_PORT = {MIXED_PORT}",
            1,
        ),
        (
            f"{SERVICE}/MishkaTunService.kt",
            "?: if (viaProxy) RuntimeOverrideBuilder.DEFAULT_MIXED_PORT else 7890",
            f"?: if (viaProxy) RuntimeOverrideBuilder.DEFAULT_MIXED_PORT else {MIXED_PORT}",
            1,
        ),
        (
            f"{SERVICE}/MihomoRunner.kt",
            'var externalController: String = "127.0.0.1:9090"',
            f'var externalController: String = "{EXT_CTL}"',
            1,
        ),
        (
            f"{KOTLIN_DIR}/data/api/MihomoApiClient.kt",
            'private val baseUrl: String = "http://127.0.0.1:9090"',
            f'private val baseUrl: String = "http://{EXT_CTL}"',
            1,
        ),
        (
            f"{KOTLIN_DIR}/domain/model/ConfigurationOverride.kt",
            '?: "127.0.0.1:9090"',
            f'?: "{EXT_CTL}"',
            1,
        ),
        (
            f"{PLATFORM}/ProxyServiceController.kt",
            'val externalController: String = "127.0.0.1:9090"',
            f'val externalController: String = "{EXT_CTL}"',
            1,
        ),
        (
            f"{KOTLIN_DIR}/ui/screen/home/StatusSection.kt",
            "private const val TPROXY_INBOUND_PORT: Int = 7895",
            f"private const val TPROXY_INBOUND_PORT: Int = {TPROXY_PORT}",
            1,
        ),
        # ── 注释里的 grep 锚点与示例值：改了命名空间却留着旧锚点，排障时会 grep 不到东西 ──
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "// 低于 sing-tun 默认 rule index 9000，保证先匹配（数字越小优先级越高）",
            f"// 低于注入给 sing-tun 的 rule index（fork = {ROOT_TUN_RULE_INDEX}），保证先匹配（数字越小优先级越高）",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "从 10000+ 起，7999 安全",
            f"从 10000+ 起，{PRIORITY_FWMARK} 安全",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            'grep "mishka:tether:"',
            f'grep "{NS}:tether:"',
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "mishka:tether:<tag>",
            f"{NS}:tether:<tag>",
            2,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "- PROXY-TPROXY：ip rule priority 7999 存在 或 `mishka_tether` chain 存在",
            f"- PROXY-TPROXY：ip rule priority {PRIORITY_FWMARK} 存在 或 `{NS}_tether` chain 存在",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "tproxy-port=7895",
            f"tproxy-port={TPROXY_PORT}",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "dns.listen=0.0.0.0:1053",
            f"dns.listen=0.0.0.0:{DNS_PORT}",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "`mishka_tproxy_pre` / `mishka_tproxy_out`",
            f"`{NS}_tproxy_pre` / `{NS}_tproxy_out`",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            "priority 7999 ip rule",
            f"priority {PRIORITY_FWMARK} ip rule",
            1,
        ),
        (
            f"{SERVICE}/RuntimeOverrideBuilder.kt",
            "// sing-tun 默认值也是 2022 / 9000，此处显式注入避免上游默认值漂移",
            "// sing-tun 默认值也是 2022 / 9000，此处显式注入避免上游默认值漂移"
            f"（fork 注入 {ROOT_TUN_TABLE} / {ROOT_TUN_RULE_INDEX}，与上游实例错开）",
            1,
        ),
        (
            f"{KOTLIN_DIR}/domain/model/ConfigurationOverride.kt",
            "默认 `127.0.0.1:9090`",
            f"默认 `{EXT_CTL}`",
            1,
        ),
        (
            f"{SERVICE}/RootTetherHijacker.kt",
            "// 与 box_for_magisk / Surfing / ClashforMagisk 选值一致（0x01000000/0x01000000）。",
            f"// fork 取 {MARK}/{MARK}（同为 bit 24），与上游实例的 mark 错开。",
            1,
        ),
        (
            f"{SERVICE}/RootTproxyApplier.kt",
            '/** 生成 `-m comment --comment "mishka:tproxy:<label>"` 片段；追加到 iptables 命令末尾。 */',
            f'/** 生成 `-m comment --comment "{NS}:tproxy:<label>"` 片段；追加到 iptables 命令末尾。 */',
            1,
        ),
        (
            f"{SERVICE}/RuntimeOverrideBuilder.kt",
            "`0.0.0.0:1053`",
            f"`0.0.0.0:{DNS_PORT}`",
            1,
        ),
        (
            f"{SERVICE}/RuntimeOverrideBuilder.kt",
            "--to-ports 1053",
            f"--to-ports {DNS_PORT}",
            1,
        ),
        *[
            (f"app/src/main/res/values{suffix}/strings.xml", "127.0.0.1:9090", EXT_CTL, 1)
            for suffix in ("", "-ru", "-zh-rCN", "-zh-rTW")
        ],
    ]


# 改写完成后必须一处不剩的字符串。上游将来新增同类代码时，这里会先红 ——
# 比等到两个包同时跑起来互相拆台要便宜得多。
FORBIDDEN = [
    "${context.packageName}.",
    "mishka_tproxy",
    "mishka_tether",
    "mishka_dns_",
    "mishka_probe_",
    "mishka:tproxy:",
    "mishka:tether:",
    "-f libmihomo_runner.so",
    "0x01000000",
    "7895",
    "1053",
    'ROOT_TUN_DEVICE, "Mishka"',
    'DEFAULT_TUN_DEVICE = "Mishka"',
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
        print("::error::改写后仍有未错开的全局命名：", file=sys.stderr)
        for h in hits:
            print(f"  - {h}", file=sys.stderr)
        return 1

    print(
        f"已改写为 fork 身份：appId={args.app_id} label={args.label} "
        f"tun={TUN_DEVICE} 内核态前缀={NS} tproxy={TPROXY_PORT} dns={DNS_PORT} "
        f"mixed={MIXED_PORT} extCtl={EXT_CTL}（共 {len(edits)} 项，仅工作区，不入库）"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
