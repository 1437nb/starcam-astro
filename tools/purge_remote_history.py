#!/usr/bin/env python3
"""把净化后的历史强推到远端（GitHub API 版），并重置受影响的 tag。

背景（2026-09-20 事故）：
  tools/gh_socks_tunnel.py 里硬编码了服务器 root 口令 + IP，随提交进入公开
  仓库并暴露 2 天。本地已用 git-filter-repo 重写全部历史剔除该口令，
  现需把远端 main 与受影响 tag 一并指向净化后的提交，使含口令的旧提交
  不再被任何 ref 引用。

为何不能普通推送：普通推送会在旧提交之上叠加新提交，含口令的旧提交
仍然可达（任何按 SHA 访问的历史都会被读到）。必须**强制更新 ref**。

做什么：
  1. 从 BASE（本地/远端共有的最后提交）开始，逐个用 Git Data API 重建
     本地提交（blob → tree → commit），跳过 .github/workflows/（token 缺
     workflow scope，且远端已有该文件，跳过即保留）。
  2. 强制 PATCH refs/heads/main 指向新建的链尾。
  3. 强制更新受影响 tag（v1.5.57/58/59/60/61）指向新链上的对应提交。
     tag 走 refs/tags/<name> 的 force PATCH；同时把旧 Release 的
     target_commitish 指向新提交（Release 本身不变，仍保留 APK 资产）。

用法：
    python tools/purge_remote_history.py            # 演练（只打印计划）
    python tools/purge_remote_history.py --execute  # 真正执行
"""

import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

OWNER = "1437nb"
REPO = "starcam-astro"
BRANCH = "main"
WD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GIT = os.environ.get("GIT_EXE", "git")

# 本地/远端共有的最后一个提交（v1.5.53 的 README 收尾）
BASE = "deee8f4b2bb45a520cddfd291abd2971ccec80cb"

# 受影响的 tag → 该 tag 应在本地历史中对应的提交（净化后）
TAG_REFS = {
    "v1.5.57": None,   # 下面按提交信息定位
    "v1.5.58": None,
    "v1.5.59": None,
    "v1.5.60": None,
    "v1.5.61": None,
}
# tag → 匹配的提交信息前缀（用于在本地历史里定位）
TAG_HINTS = {
    "v1.5.57": "R8 缺注解类导致 release 构建失败",
    "v1.5.58": "官方引擎星点来源优先级",
    "v1.5.59": "识别日志——失败原因与现场留存",
    "v1.5.60": "修复 9-17 照片识别失败与旧照片回归",
    "v1.5.61": "SEP 提星阈值语义误用",
}


def git(*args, text=True):
    return subprocess.run((GIT,) + args, cwd=WD, capture_output=True,
                          text=text, check=True).stdout


def get_token():
    out = subprocess.run((GIT, "credential", "fill"),
                         input="protocol=https\nhost=github.com\n\n",
                         capture_output=True, text=True, cwd=WD).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    sys.exit("找不到 GitHub 凭据（git credential fill 未返回 password）")


TOK = get_token()


def gh(method, path, payload=None, ok404=False):
    headers = {
        "Authorization": "Bearer " + TOK,
        "User-Agent": "StarCam-purge/1.0",
        "Accept": "application/vnd.github+json",
    }
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request("https://api.github.com" + path, data=data,
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        if ok404 and e.code == 404:
            return None
        sys.exit(f"HTTP {e.code} {method} {path}: "
                 f"{e.read().decode('utf-8', 'replace')[:500]}")


def local_shas():
    """BASE..HEAD 的本地提交（旧→新）"""
    return git("rev-list", "--reverse", f"{BASE}..HEAD").split()


def rebuild_chain(plan_only):
    """逐个重建提交，返回 (新链尾sha, {本地sha: 新sha})"""
    parent = BASE
    mapping = {}
    for i, sha in enumerate(local_shas(), 1):
        subject = git("log", "-1", "--format=%s", sha)[:58]
        # 该提交改了哪些文件
        raw = git("diff", "--raw", f"{sha}^", sha)
        items = []
        skipped = []
        for line in raw.splitlines():
            meta, path = line.split("\t", 1)
            fields = meta.split()
            newmode, status = fields[1], fields[4][0]
            # 2026-09-20：token 已补 workflow scope（实测 tree 含该路径创建成功），
            # 故 workflow 文件照常上传，不再跳过 —— 否则重建后的 main 会丢 CI。
            if status == "D":
                items.append({"path": path, "mode": newmode, "type": "blob", "sha": None})
                continue
            content = subprocess.run((GIT, "show", f"{sha}:{path}"), cwd=WD,
                                     capture_output=True, check=True).stdout
            if not plan_only:
                blob = gh("POST", f"/repos/{OWNER}/{REPO}/git/blobs",
                          {"content": base64.b64encode(content).decode(), "encoding": "base64"})
                items.append({"path": path, "mode": newmode, "type": "blob", "sha": blob["sha"]})
            else:
                items.append({"path": path, "mode": newmode, "type": "blob", "sha": "?"})
        if plan_only:
            print("  %2d/%d %s %-58s (%d 文件%s)" % (
                i, len(local_shas()), sha[:9], subject, len(items),
                ", 跳过 workflow" if skipped else ""))
            mapping[sha] = f"NEW{i}"
            continue
        if not items:
            print("  %2d %s %-52s → 复用 parent（仅 workflow 变更）" % (i, sha[:9], subject))
            mapping[sha] = parent
            continue
        base_tree = gh("GET", f"/repos/{OWNER}/{REPO}/git/commits/{parent}")["tree"]["sha"]
        tree = gh("POST", f"/repos/{OWNER}/{REPO}/git/trees",
                  {"base_tree": base_tree, "tree": items})
        msg = subprocess.run((GIT, "log", "-1", "--format=%B", sha), cwd=WD,
                             capture_output=True, text=True, check=True).stdout.strip()
        ident = {"name": "1437nb", "email": "1437nb@users.noreply.github.com",
                 "date": git("log", "-1", "--format=%aI", sha)}
        commit = gh("POST", f"/repos/{OWNER}/{REPO}/git/commits",
                    {"message": msg, "tree": tree["sha"], "parents": [parent],
                     "author": ident, "committer": ident})
        mapping[sha] = commit["sha"]
        parent = commit["sha"]
        print("  %2d %s %-52s → %s" % (i, sha[:9], subject, parent[:9]))
    return (parent if not plan_only else None), mapping


def main():
    plan_only = "--execute" not in sys.argv
    shas = local_shas()
    print("=== %s ===" % ("演练（不改远端）" if plan_only else "执行（改远端）"))
    print("BASE = %s (%s)" % (BASE, git("log", "-1", "--format=%s", BASE)[:50]))
    print("本地待重建提交: %d 个" % len(shas))

    remote_main = gh("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/{BRANCH}")["object"]["sha"]
    print("远端 main 当前: %s" % remote_main[:10])

    # tag 定位
    print("\n=== 受影响 tag 的新目标 ===")
    tag_targets = {}
    for tag, hint in TAG_HINTS.items():
        # 在本地历史里找匹配提交
        found = None
        for sha in shas:
            if hint in git("log", "-1", "--format=%s", sha):
                found = sha
                break
        tag_targets[tag] = found
        print("  %-8s → %s %s" % (tag, (found or "未找到")[:9],
                                  git("log", "-1", "--format=%s", found)[:44] if found else ""))

    print("\n=== 重建提交链 ===")
    new_tip, mapping = rebuild_chain(plan_only)

    if plan_only:
        print("\n（演练结束。加 --execute 真正执行）")
        return

    print("\n新链尾: %s" % new_tip[:10])
    print("\n=== 强制更新 main ===")
    gh("PATCH", f"/repos/{OWNER}/{REPO}/git/refs/heads/{BRANCH}",
       {"sha": new_tip, "force": True})
    print("main →", gh("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/{BRANCH}")["object"]["sha"][:10])

    print("\n=== 强制更新受影响 tag ===")
    for tag, old_local in tag_targets.items():
        if not old_local:
            print("  跳过 %s（未定位到本地提交）" % tag)
            continue
        new_sha = mapping.get(old_local)
        if not new_sha or new_sha.startswith("NEW"):
            print("  跳过 %s（无新目标）" % tag)
            continue
        # tag 对象可能不存在，用 force 创建/更新 refs/tags/<tag> 指向 commit
        try:
            gh("PATCH", f"/repos/{OWNER}/{REPO}/git/refs/tags/{tag}",
               {"sha": new_sha, "force": True})
            print("  %s → %s" % (tag, new_sha[:10]))
        except SystemExit:
            print("  %s 更新失败（可能需先删除旧 ref）" % tag)

    print("\n=== 完成 ===")
    print("远端 main:", gh("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/{BRANCH}")["object"]["sha"][:10])


if __name__ == "__main__":
    main()
