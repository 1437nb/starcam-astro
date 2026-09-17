#!/usr/bin/env python3
"""上传 APK 到已存在的 GitHub Release（走 api.github.com，本机可达）。

用法:
    python gh_upload_asset.py <tag> <apk路径> [<apk路径> ...]

凭据从 `git credential fill` 取（与 tools/gh_api_push.py 同一来源）。
已存在的同名资产会先删除再上传（覆盖语义）。
"""
import http.client
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

OWNER = "1437nb"
REPO = "starcam-astro"
WD = os.path.dirname(os.path.abspath(__file__))


def get_token():
    out = subprocess.run(
        ("git", "credential", "fill"),
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True, cwd=WD,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    sys.exit("找不到 GitHub 凭据（git credential fill 未返回 password）")


TOK = get_token()


def gh(method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {
        "Authorization": "Bearer " + TOK,
        "User-Agent": "StarCam-gh-upload/1.0",
        "Accept": "application/vnd.github+json",
    }
    if data:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request("https://api.github.com" + path,
                                 data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        sys.exit("HTTP %d %s %s: %s" % (e.code, method, path,
                                        e.read().decode("utf-8", "replace")[:400]))


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    tag, paths = argv[1], argv[2:]
    rel = gh("GET", "/repos/%s/%s/releases/tags/%s" % (OWNER, REPO, tag))
    print("Release: %s (id=%d)" % (rel["html_url"], rel["id"]))

    for path in paths:
        name = os.path.basename(path)
        if not os.path.isfile(path):
            print("MISSING %s" % path)
            continue
        # 覆盖语义：同名资产先删
        for a in rel.get("assets", []):
            if a["name"] == name:
                gh("DELETE", "/repos/%s/%s/releases/assets/%d" % (OWNER, REPO, a["id"]))
                print("  已删除旧同名资产:", name)
        content = open(path, "rb").read()
        # 资产名走 URL query，必须自己拼 raw path 并用 http.client 发：
        # urllib.request 会把 URL 里的 %XX 重新按 latin-1 编码 → 中文被吞成 "."。
        quoted = urllib.parse.quote(name, encoding="utf-8", safe="")
        conn = http.client.HTTPSConnection("uploads.github.com", timeout=3600)
        try:
            conn.request(
                "POST",
                "/repos/%s/%s/releases/%d/assets?name=%s" % (OWNER, REPO, rel["id"], quoted),
                body=content,
                headers={
                    "Authorization": "Bearer " + TOK,
                    "User-Agent": "StarCam-gh-upload/1.0",
                    "Content-Type": "application/vnd.android.package-archive",
                    "Content-Length": str(len(content)),
                    "Accept": "application/vnd.github+json",
                })
            resp = conn.getresponse()
            raw = resp.read()
            if resp.status not in (200, 201):
                print("  上传失败 %s HTTP %d: %s" % (name, resp.status,
                                                 raw.decode("utf-8", "replace")[:400]))
                return 1
            a = json.loads(raw)
            print("  已上传: %s (%d bytes)" % (a["browser_download_url"], a["size"]))
        finally:
            conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
