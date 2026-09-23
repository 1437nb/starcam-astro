# -*- coding: utf-8 -*-
"""补全 GitHub 仓库元数据（description / homepage / topics）。

对应《StarCam项目分析报告-合并版》风险（四）「仓库元数据缺失」：
description 与 homepage 为空时，搜索结果、社交分享卡片、仓库列表页都只显示
「No description provided」—— 对一个质量不错的项目是纯粹的可发现性损失。

凭据从 `git credential fill` 取（与 tools/gh_api_push.py 同源），
**不写入磁盘、不打印**。只输出 API 返回的最终元数据供核对。

用法: python3 tools/set_repo_meta.py

⚠️ 前置条件：本机凭据管理器里存有 github.com 的 token
（即 `git credential fill` 能返回 `password=`）。
2026-09-22 在维护者本机实测该命令返回**空**（凭据管理器里没有可用凭据），
因此这一步当时未能自动执行 —— 需要先交互式登录一次，或直接在 GitHub
网页上手动填写（本文件的 DESCRIPTION / HOMEPAGE / TOPICS 常量即现成文案）。

幂等：可重复执行，覆盖为同样的值。
topics 是**整体替换**语义（不是追加），改 TOPICS 时务必带上想保留的旧条目。
"""
import json
import subprocess
import sys
import urllib.request

OWNER = "1437nb"
REPO = "starcam-astro"
API = f"https://api.github.com/repos/{OWNER}/{REPO}"

# 报告《StarCam项目分析报告-合并版》风险(四) 的建议文案，略作扩充：
# 保留「纯离线 / 盲解天区坐标」两个定位关键词，补上引擎构成与 AR 特色，
# 便于搜索结果与分享卡片展示。
DESCRIPTION = (
    "纯离线的 Android 星空识别与天文摄影标注应用："
    "本地盲解天区坐标（astrometry.net 引擎 + 自研 8400+ 星表匹配），含 AR 实时星图"
)

# 项目没有独立站点，homepage 指向 Releases —— 那是用户下载 APK 的入口。
HOMEPAGE = f"https://github.com/{OWNER}/{REPO}/releases"

# 现有 topics（不动） + 补充检索词。上限 20，这里 11 个。
TOPICS = [
    "android",
    "astronomy",
    "astrophotography",
    "offline-app",
    "stargazing",
    "astrometry",
    "plate-solving",
    "star-catalog",
    "ar",
    "kotlin",
    "jetpack-compose",
]


def token() -> str:
    try:
        p = subprocess.run(
            ["git", "credential", "fill"],
            input="protocol=https\nhost=github.com\n\n",
            capture_output=True, text=True, timeout=90, cwd=r"C:\starword",
        )
    except subprocess.TimeoutExpired:
        print("ERROR: git credential fill 超时（凭据管理器可能在等待交互）", file=sys.stderr)
        sys.exit(1)
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    print("ERROR: 未能从 git credential 取到凭据", file=sys.stderr)
    sys.exit(1)


def call(url: str, method: str, payload: dict, tok: str) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        method=method,
        headers={
            "Authorization": f"token {tok}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "starcam-meta",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def main() -> None:
    tok = token()

    # 1) description + homepage
    out = call(API, "PATCH", {"description": DESCRIPTION, "homepage": HOMEPAGE}, tok)
    print("PATCH ok  description =", out.get("description"))
    print("PATCH ok  homepage    =", out.get("homepage"))

    # 2) topics（独立端点）
    out2 = call(f"{API}/topics", "PUT", {"names": TOPICS}, tok)
    print("PUT   ok  topics      =", out2.get("names"))

    print("\n--- 复核（读回）---")
    req = urllib.request.Request(
        API, headers={"Accept": "application/vnd.github+json", "User-Agent": "starcam-meta"},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.loads(r.read().decode("utf-8"))
    print("description =", d.get("description"))
    print("homepage    =", d.get("homepage"))
    print("topics      =", d.get("topics"))


if __name__ == "__main__":
    main()
