#!/usr/bin/env python3
"""汇总 Compose 编译报告：restartable 的 skippable 覆盖率与 unstable 参数清单。

报告由 CI 临时注入 composeCompiler { reportsDestination } 生成（上游 AGENTS.md 的验证项，
仓库源码里不留该配置）。本脚本只读报告，不参与构建，故不改动任何构建参数。

非 skippable 与 unstable 参数只报告、不失败：它们是重组开销，不影响正确性，硬失败会把
「新增一个 composable」直接变成红 CI。真正的门禁是报告可见、改动前后可对比。
"""

from __future__ import annotations

import glob
import os
import re
import sys

REPORT_DIR = "app/build/compose_reports"
FUNC_RE = re.compile(r"^(restartable(?:\s+skippable)?)\s+.*?\bfun\s+(\w+)")
UNSTABLE_RE = re.compile(r"^\s+unstable\s+(.+)$")


def collect() -> tuple[int, int, list[str], list[str], list[str]]:
    files = sorted(glob.glob(os.path.join(REPORT_DIR, "*-composables.txt")))
    restartable = 0
    skippable = 0
    not_skippable: list[str] = []
    unstable: list[str] = []
    for path in files:
        current: str | None = None
        for raw in open(path, encoding="utf-8"):
            line = raw.rstrip("\n")
            m = FUNC_RE.match(line)
            if m:
                current = m.group(2)
                restartable += 1
                if "skippable" in m.group(1):
                    skippable += 1
                else:
                    not_skippable.append(current)
                continue
            u = UNSTABLE_RE.match(line)
            if u and current:
                unstable.append(f"{current}: {u.group(1)}")
    return restartable, skippable, not_skippable, unstable, files


def main() -> int:
    restartable, skippable, not_skippable, unstable, files = collect()
    if not files:
        print(
            f"::error::未找到 compose 报告（{REPORT_DIR}），检查 reportsDestination 是否生效",
            file=sys.stderr,
        )
        return 1

    ratio = f"{skippable / restartable:.1%}" if restartable else "n/a"
    out: list[str] = [
        "## Compose 编译报告",
        "",
        f"- restartable：**{restartable}**",
        f"- restartable + skippable：**{skippable}**（{ratio}）",
        f"- restartable 但不可 skip：**{len(not_skippable)}**",
        f"- unstable 参数：**{len(unstable)}**",
        "",
    ]
    if not_skippable:
        out += ["### restartable 但不可 skip", ""]
        out += [f"- `{name}`" for name in not_skippable[:40]]
        if len(not_skippable) > 40:
            out += [f"- …另有 {len(not_skippable) - 40} 个"]
        out += [""]
    if unstable:
        out += ["### unstable 参数", ""]
        out += [f"- `{item}`" for item in unstable[:40]]
        if len(unstable) > 40:
            out += [f"- …另有 {len(unstable) - 40} 个"]
        out += [""]

    text = "\n".join(out)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
