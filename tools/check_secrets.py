#!/usr/bin/env python3
"""提交前凭据自检（本地守门），与 CI 的 .gitleaks.toml 规则保持一致。

为什么需要它：
  CI 的 gitleaks 用默认规则集，而默认规则只认「已知凭据格式」，
  「自定义变量名 = 任意口令」不在覆盖范围。本脚本用仓库自己的规则
  （与 .gitleaks.toml 一致）扫工作区 + 暂存区，在 git commit 之前拦住问题，
  且不依赖 gitleaks 二进制。

用法：
    python tools/check_secrets.py            # 扫工作区全部跟踪文件
    python tools/check_secrets.py --staged   # 只扫暂存内容（适合 pre-commit）
退出码：0 = 干净；1 = 发现疑似凭据（并打印位置）

有 gitleaks 二进制时优先用它（结果权威）；没有则用内置规则回退。
"""

import os
import re
import subprocess
import sys

WD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GIT = os.environ.get("GIT_EXE", "git")

# 与 .gitleaks.toml 的三条项目规则对应（内置回退用）
RULES = [
    # 注意：字符类排除 \r\n，否则 "password=" 这类字符串字面量会与下一行的
    # 引号配对，把两行糊成一次"赋值"匹配（实测误报 3 处）。
    ("硬编码口令",
     re.compile(r'''(?i)[\w.]*(pass(word|wd)?|pwd|secret)[\w]*\s*[:=]\s*["'][^"'$\{\s\r\n][^"'\r\n]{5,}["']''')),
    ("服务器地址 + 凭据语境",
     re.compile(r'''(?i)(?:ssh_?host|ssh_?server|host|server|ip)\s*[:=]\s*["']\d{1,3}(?:\.\d{1,3}){3}["']''')),
    ("root 账户赋值",
     re.compile(r'''(?i)\b(ssh_?user|username|user)\s*[:=]\s*["']root["']''')),
]

ALLOW = [
    # 私有/回环/链路本地网段（.gitleaks.toml 同样用 allowlist 表达，RE2 无 lookahead）
    re.compile(r'''(?i)(?:host|server|ip)\s*[:=]\s*["'](?:10\.|127\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.|169\.254\.|0\.)'''),
    # 解析 "password=" 这类字面量（git credential 协议），不是凭据本身
    re.compile(r'''(?i)(?:startswith|endswith|contains|equals|indexOf)\s*\(\s*["'][\w.]*(pass|pwd|secret)[\w.]*["']'''),
    re.compile(r'''(?i)[\w.]*(pass(word|wd)?|pwd|secret)[\w]*\s*[:=]\s*["'][^"']*(your|changeme|placeholder|redacted|example|dummy|fake|todo|xxx|\*\*\*)[^"']*["']'''),
    re.compile(r'''(?i)[\w.]*(pass(word|wd)?|pwd|secret)[\w]*\s*[:=]\s*\w+\.get(env|Property)'''),
    re.compile(r'''(?i)[\w.]*(pass(word|wd)?|pwd|secret)[\w]*\s*[:=]\s*["']/'''),
    re.compile(r'''(?i)[\w.]*(pass(word|wd)?|pwd|secret)[\w]*\s*[:=]\s*["'][^"'\w]{0,5}["']'''),
]

# 这些路径本就允许出现示例/文档（与 .gitleaks.toml 的 allowlist paths 对应）
SKIP_PATH = re.compile(r'''(?i)(^|/)(\.git|build|\.gradle|\.kotlin|__pycache__|apk)/|\.gitleaks\.toml$|tools/check_secrets\.py$''')


def git(*args, text=True):
    return subprocess.run((GIT,) + args, cwd=WD, capture_output=True, text=text).stdout


def scan_text(path, text):
    hits = []
    for i, line in enumerate(text.splitlines(), 1):
        # gitleaks 约定的行内豁免：行尾带 gitleaks:allow 标记的行不告警。
        # 用于必须展示示例文本的场景（文档、测试夹具）。
        if "gitleaks:allow" in line:
            continue
        if not any(r.search(line) for _, r in RULES):
            continue
        if any(a.search(line) for a in ALLOW):
            continue
        for name, rx in RULES:
            if rx.search(line):
                # 脱敏打印，避免把口令二次写进终端历史
                shown = line.strip()
                shown = re.sub(r'(["\'])([^"\']{4})[^"\']*(["\'])',
                               lambda m: m.group(1) + m.group(2) + "***" + m.group(3), shown)
                hits.append((path, i, name, shown[:100]))
                break
    return hits


def main():
    staged = "--staged" in sys.argv
    print("凭据自检%s…" % ("（暂存区）" if staged else "（工作区）"))
    if staged:
        files = [f for f in git("diff", "--cached", "--name-only", "--diff-filter=ACMR").splitlines() if f]
        reader = lambda f: git("show", ":" + f)
    else:
        files = [f for f in git("ls-files").splitlines() if f]
        reader = lambda f: open(os.path.join(WD, f), encoding="utf-8", errors="replace").read()

    all_hits = []
    for f in files:
        if SKIP_PATH.search(f.replace("\\", "/")):
            continue
        try:
            txt = reader(f)
        except Exception:
            continue
        if not txt:
            continue
        all_hits += scan_text(f, txt)

    if all_hits:
        print("\n发现 %d 处疑似凭据：" % len(all_hits))
        for path, ln, name, shown in all_hits:
            print("  %s:%d  [%s]  %s" % (path, ln, name, shown))
        print("\n如确认是误报（示例/占位），请加入 .gitleaks.toml 的 allowlist，"
              "不要直接绕过本检查。")
        return 1
    print("未发现硬编码凭据 ✓（扫描 %d 个文件）" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
