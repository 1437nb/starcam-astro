#!/usr/bin/env python3
"""通过 GitHub Git Data API 推送本地提交 / 发布 Release。

为什么需要它：本机 `git push` 不可用（github.com:443 被阻断，实测 Connection
reset / timeout；ssh.github.com:443 能连通但 publickey 被拒）。`api.github.com`
可达，所以改走官方 REST API 逐提交重建：blobs → tree(base_tree) → commit → ref。

凭据从 `git credential fill` 读取（凭据管理器里已存的 github.com token），
不落盘、不打印。

用法：
    python tools/gh_api_push.py push           # 推送 main 上尚未上传的提交
    python tools/gh_api_push.py release <tag> <commit> <notes-file> [<apk> ...]

关键坑（v1.5.55 踩过）：
    上传 blob 必须用 `git show <sha>:<path>` 读内容（LF 规范化后的对象），
    **不能**读工作区文件——`core.autocrlf=true` 下工作区是 CRLF，会把整个仓库
    的行尾污染成 CRLF。若已污染：`git add --renormalize .` 再提交修回。
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


def git(*args):
    return subprocess.run((GIT,) + args, cwd=WD, capture_output=True, text=True,
                          check=True).stdout.strip()


def get_token():
    out = subprocess.run((GIT, "credential", "fill"),
                         input="protocol=https\nhost=github.com\n\n",
                         capture_output=True, text=True, cwd=WD).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    sys.exit("找不到 GitHub 凭据（git credential fill 未返回 password）")


TOK = get_token()


def has_workflow_scope():
    """token 是否带 workflow scope（GitHub 在任意响应头里给出 x-oauth-scopes）。"""
    req = urllib.request.Request(
        f"https://api.github.com/repos/{OWNER}/{REPO}",
        headers={"Authorization": "Bearer " + TOK,
                 "User-Agent": "StarCam-gh-api-push/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return "workflow" in (r.headers.get("X-OAuth-Scopes") or "")
    except urllib.error.HTTPError:
        return False


def gh(method, path, payload=None):
    headers = {
        "Authorization": "Bearer " + TOK,
        "User-Agent": "StarCam-gh-api-push/1.0",
        "Accept": "application/vnd.github+json",
    }
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request("https://api.github.com" + path, data=data,
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code} {method} {path}: "
                 f"{e.read().decode('utf-8', 'replace')[:600]}")


def remote_head():
    return gh("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/{BRANCH}")["object"]["sha"]


def find_local_base(remote_sha, remote_tree):
    """找出远端 head 在本地对应的提交，作为 diff 基线。

    两种匹配方式（依次尝试）：
      1. tree sha 完全相同 —— 常规情况（本地与远端内容一致）；
      2. commit message 首行相同 —— 远端含本地推不上去的文件时（如
         .github/workflows/，见下方 workflow scope 说明），tree 必然不同，
         此时按提交信息认领。若二者都找不到则报错，避免用错误基线推送。
    """
    lines = git("log", "--format=%H %T", "-200", "HEAD").splitlines()
    for line in lines:
        h, t = line.split()
        if t == remote_tree:
            return h, "tree"
    remote_msg = gh("GET", f"/repos/{OWNER}/{REPO}/git/commits/{remote_sha}")["message"]
    remote_first = remote_msg.strip().splitlines()[0].strip()
    for line in lines:
        h = line.split()[0]
        msg = subprocess.run((GIT, "log", "-1", "--format=%s", h), cwd=WD,
                             capture_output=True, text=True).stdout.strip()
        if msg == remote_first:
            return h, "message"
    return None, None


def remote_tree_files(tree_sha):
    """递归列出远端一棵树的全部 blob：{path: blob_sha}。"""
    out = {}
    stack = [("", tree_sha)]
    while stack:
        prefix, ts = stack.pop()
        d = gh("GET", f"/repos/{OWNER}/{REPO}/git/trees/{ts}")
        for e in d.get("tree", []):
            p = prefix + e["path"]
            if e["type"] == "tree":
                stack.append((p + "/", e["sha"]))
            else:
                out[p] = e["sha"]
    return out


def local_tree_files(sha):
    """列出本地一个提交的全部 blob：{path: blob_sha}（git ls-tree -r）。"""
    out = {}
    for line in git("ls-tree", "-r", sha).splitlines():
        meta, path = line.split("	", 1)
        out[path] = meta.split()[2]
    return out


def upload_commit(local_sha, parent_sha):
    """把一个本地提交重建到远端（parent 为远端提交 sha）。

    差集算法：直接对比「远端父树的文件清单」与「本地提交的文件清单」。

    为什么不能用 `git diff --raw <parent_sha> <local_sha>`：parent_sha 是本脚本
    通过 API 创建的提交，**本地仓库里往往不存在**，git diff 会失败；旧代码此时
    退化成「对空树做全量 diff」，而那种 diff 只有新增、表达不出删除 —— 实测删掉的
    文件在远端依然存在（CatalogDepthAB.kt 的删除静默丢失，CI 继续跑它并失败）。
    """
    base_tree = gh("GET", f"/repos/{OWNER}/{REPO}/git/commits/{parent_sha}")["tree"]["sha"]
    parent_files = remote_tree_files(base_tree)
    local_files = local_tree_files(local_sha)

    items = []
    skipped_workflow = []
    # 1) 新增 / 修改
    for path, lsha in sorted(local_files.items()):
        if parent_files.get(path) == lsha:
            continue                                    # 未变
        # .github/workflows/ 的写入需要 token 具备 workflow scope。token 缺该 scope 时
        # GitHub 对这类路径一律回 404，Contents API 与 Git Data API 都是——此时上传会
        # 直接失败，因此先探测 scope，缺了才跳过（曾因无条件跳过导致 CI 配置改不动）。
        if path.startswith(".github/workflows/") and not has_workflow_scope():
            skipped_workflow.append(path)
            continue
        # 关键：从 git 对象读（LF），不读工作区（CRLF）
        content = subprocess.run((GIT, "show", f"{local_sha}:{path}"), cwd=WD,
                                 capture_output=True, check=True).stdout
        blob = gh("POST", f"/repos/{OWNER}/{REPO}/git/blobs",
                  {"content": base64.b64encode(content).decode(), "encoding": "base64"})
        # 本仓库全部是普通文件，统一 100644
        items.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    # 2) 删除：父树里有、本地没有 → sha=None（删除语义由 sha 表达，mode 给合法值）
    for path in sorted(parent_files):
        if path in local_files:
            continue
        if path.startswith(".github/workflows/") and not has_workflow_scope():
            skipped_workflow.append(path)
            continue
        items.append({"path": path, "mode": "100644", "type": "blob", "sha": None})

    if not items:
        # 本次提交只改了 .github/workflows/ 下的文件（全被跳过）→ 无可上传内容，
        # 直接复用 parent，不产生空提交（GitHub 不允许 base_tree 配空 tree）。
        return parent_sha, 0, skipped_workflow
    tree = gh("POST", f"/repos/{OWNER}/{REPO}/git/trees",
              {"base_tree": base_tree, "tree": items})
    msg = subprocess.run((GIT, "log", "-1", "--format=%B", local_sha), cwd=WD,
                         capture_output=True, text=True, check=True).stdout.strip()
    ident = {"name": "1437nb", "email": "1437nb@users.noreply.github.com",
             "date": git("log", "-1", "--format=%aI", local_sha)}
    commit = gh("POST", f"/repos/{OWNER}/{REPO}/git/commits",
                {"message": msg, "tree": tree["sha"], "parents": [parent_sha],
                 "author": ident, "committer": ident})
    return commit["sha"], len(items), skipped_workflow


def cmd_push():
    remote = remote_head()
    local = git("rev-parse", "HEAD")
    if remote == local:
        print("已同步，无需推送")
        return
    # 强制闸门：本脚本是绕过本地 git 钩子（pre-commit / pre-push）的**唯一**
    # 推送通道，因此必须自己先扫一遍凭据。发现疑似凭据即中止（退出码非 0）。
    print("推送前凭据自检…")
    chk = subprocess.run(
        (sys.executable, os.path.join(WD, "tools", "check_secrets.py")),
        cwd=WD, capture_output=True, text=True,
    )
    print(chk.stdout.strip())
    if chk.returncode != 0:
        sys.exit("凭据自检未通过，已中止推送。处理后再重试（误报请加 .gitleaks.toml allowlist）。")
    # 远端 sha 可能不在本地（API 创建的提交）→ 用 tree sha / 提交信息找本地等价提交
    remote_tree = gh("GET", f"/repos/{OWNER}/{REPO}/git/commits/{remote}")["tree"]["sha"]
    base, how = find_local_base(remote, remote_tree)
    if base is None:
        sys.exit(f"远端 {remote[:9]} 在本地找不到等价提交；请先 "
                 f"git fetch https://ghfast.top/https://github.com/{OWNER}/{REPO}.git")
    print(f"基线匹配方式：{how}")
    todo = git("rev-list", "--reverse", f"{base}..HEAD").split()
    if not todo:
        print("没有待推送的提交（远端可能含本地没有的 API 提交）")
        return
    print(f"远端 {remote[:9]} → 待推 {len(todo)} 个提交")
    parent = remote
    all_skipped = []
    for sha in todo:
        parent, n, skipped = upload_commit(sha, parent)
        all_skipped += skipped
        print(f"  {sha[:9]} ({n} 文件) → {parent[:9]}")
    gh("PATCH", f"/repos/{OWNER}/{REPO}/git/refs/heads/{BRANCH}",
       {"sha": parent, "force": False})
    print("推送完成，远端 main =", remote_head())
    if all_skipped:
        print()
        print("⚠️  以下文件被 GitHub 拒绝（token 缺 workflow scope，需手动通过网页添加）：")
        for p in sorted(set(all_skipped)):
            print("   ", p)
        print("    手动方式：GitHub 网页 → Add file → Create new file →",
              all_skipped[0])


def cmd_release(tag, commit, notes_file, *apks):
    try:
        rel = gh("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{tag}")
        print("Release 已存在：", rel["html_url"])
    except SystemExit:
        body = open(notes_file, encoding="utf-8").read()
        rel = gh("POST", f"/repos/{OWNER}/{REPO}/releases",
                 {"tag_name": tag, "target_commitish": commit, "name": tag,
                  "body": body, "draft": False, "prerelease": False})
        print("已创建 Release：", rel["html_url"])
    existing = {a["name"] for a in rel.get("assets", [])}
    for path in apks:
        name = os.path.basename(path)
        if name in existing:
            print("  资产已存在，跳过：", name)
            continue
        content = open(path, "rb").read()
        url = (f"https://uploads.github.com/repos/{OWNER}/{REPO}/releases/{rel['id']}"
               f"/assets?name={urllib.request.quote(name)}")
        req = urllib.request.Request(
            url, data=content, method="POST",
            headers={"Authorization": "Bearer " + TOK,
                     "User-Agent": "StarCam-gh-api-push/1.0",
                     "Content-Type": "application/vnd.android.package-archive",
                     "Content-Length": str(len(content)),
                     "Accept": "application/vnd.github+json"})
        try:
            with urllib.request.urlopen(req, timeout=1800) as r:
                a = json.loads(r.read())
                print("  已上传：", a["browser_download_url"])
        except urllib.error.HTTPError as e:
            sys.exit(f"上传 {name} 失败 HTTP {e.code}: "
                     f"{e.read().decode('utf-8', 'replace')[:300]}")
    print("完成：", rel["html_url"])


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "push":
        cmd_push()
    elif sys.argv[1] == "release":
        cmd_release(*sys.argv[2:])
    else:
        sys.exit(__doc__)
