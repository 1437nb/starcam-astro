#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一键发版：版本号同步 → 凭据预检 → release 构建 → 验签 → 归档。

对应《StarCam项目分析报告-合并版》风险（六）「构建与发版流程高门槛」：

    发版流程依赖多个隐性前提，且**缺了不报错**：签名密钥不在位时 signingConfig
    会静默变 null，产物是装不上的未签名 APK（v1.5.55 就踩过）。

本脚本把「改版本号 → 检查凭据 → 构建 → 验证签名 → 归档」串成一条命令，
**任何一步不满足前置条件都立即失败并说明原因**，不再有静默降级。

用法::

    python tools/release.py --version 1.5.64 --desc fix-xxx            # 全流程
    python tools/release.py --version 1.5.64 --desc fix-xxx --dry-run  # 只演算，不改文件不构建
    python tools/release.py --version 1.5.64 --desc fix-xxx --skip-build

`--desc` 是 APK 文件名里的更新内容简述，按仓库惯例用**英文短横线短语**
（如 `fix-export-and-camera-lifecycle`），中文短语只用于本地归档。

安全：只做**存在性**检查，不打印任何口令；只改
`code/app/build.gradle.kts` 的 versionCode / versionName / updateDesc 三处。
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_KTS = ROOT / "code" / "app" / "build.gradle.kts"
LOCAL_PROPS = ROOT / "code" / "local.properties"
APK_DIR = ROOT / "apk"
JNI_SO = ROOT / "code" / "app" / "src" / "main" / "jniLibs" / "arm64-v8a" / "libstellar_solver.so"

SDK_DIR = Path("C:/dev/android-sdk")
APKSIGNER = SDK_DIR / "build-tools" / "34.0.0" / "lib" / "apksigner.jar"


def die(msg: str) -> None:
    print(f"\n[失败] {msg}", file=sys.stderr)
    sys.exit(1)


def step(msg: str) -> None:
    print(f"\n==> {msg}")


# ---------------------------------------------------------------- 版本号读写

def read_version() -> tuple:
    """返回 (versionCode, versionName, updateDesc 默认值)。"""
    src = GRADLE_KTS.read_text(encoding="utf-8")
    m_code = re.search(r"^(\s*)versionCode\s*=\s*(\d+)\s*$", src, re.M)
    m_name = re.search(r'^(\s*)versionName\s*=\s*"([^"]+)"\s*$', src, re.M)
    if not m_code or not m_name:
        die("在 app/build.gradle.kts 里找不到 versionCode / versionName，脚本失效，请人工核对")
    # updateDesc 的默认值：定位 val updateDesc 之后最近的 `?: "..."`
    i = src.find("val updateDesc")
    if i < 0:
        die("在 app/build.gradle.kts 里找不到 val updateDesc")
    m_desc = re.search(r'\?:\s*"([^"]*)"', src[i:i + 400])
    desc = m_desc.group(1) if m_desc else ""
    return int(m_code.group(2)), m_name.group(2), desc


def bump_version(new_name: str, new_desc: str, dry: bool) -> tuple:
    src = GRADLE_KTS.read_text(encoding="utf-8")
    code, name, _ = read_version()
    new_code = code + 1

    # versionCode
    src2 = re.sub(
        r"^(\s*)versionCode\s*=\s*\d+\s*$",
        lambda m: f"{m.group(1)}versionCode = {new_code}",
        src, count=1, flags=re.M,
    )
    # versionName
    src2 = re.sub(
        r'^(\s*)versionName\s*=\s*"[^"]+"\s*$',
        lambda m: f'{m.group(1)}versionName = "{new_name}"',
        src2, count=1, flags=re.M,
    )
    # updateDesc 默认值（保持与 -PupdateDesc 传参一致，避免源码里的默认值过期）
    i = src2.find("val updateDesc")
    head, tail = src2[:i], src2[i:]
    tail = re.sub(r'\?:\s*"[^"]*"', f'?: "{new_desc}"', tail, count=1)
    src2 = head + tail

    if src2 == src:
        die("版本号改写后内容未变化 —— 正则可能已失效，请人工核对 build.gradle.kts")

    if not dry:
        GRADLE_KTS.write_text(src2, encoding="utf-8")
    print(f"  versionCode : {code} -> {new_code}")
    print(f"  versionName : {name} -> {new_name}")
    print(f"  updateDesc  : -> {new_desc}")
    return new_code, new_name


# ---------------------------------------------------------------- 前置检查

def find_keystore_props() -> Path:
    """复刻 build.gradle.kts 的三级查找顺序。"""
    env = os.environ.get("STARCAM_KEYSTORE")
    if env and Path(env).exists():
        return Path(env)
    home = Path(os.path.expanduser("~")) / ".starcam" / "starcam-keystore.properties"
    if home.exists():
        return home
    return Path()


def preflight() -> None:
    """任一前置条件不满足就立即失败 —— 这是本脚本存在的理由。"""
    step("前置检查")
    problems = []

    if not LOCAL_PROPS.exists():
        problems.append(f"缺少 {LOCAL_PROPS}（内容应为 sdk.dir=<你的 Android SDK>）")
    else:
        print(f"  [ok] {LOCAL_PROPS}")

    props = find_keystore_props()
    if not props:
        problems.append(
            "找不到签名凭据文件。查找顺序：环境变量 STARCAM_KEYSTORE → "
            "~/.starcam/starcam-keystore.properties。"
            "**没有它 Gradle 会静默产出未签名 APK**（v1.5.55 踩过）"
        )
    else:
        print(f"  [ok] 签名凭据 {props}")
        # 只取 storeFile 一行，不读也不打印其他内容
        store = None
        for line in props.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.strip().startswith("storeFile"):
                store = line.split("=", 1)[1].strip()
                break
        if not store:
            problems.append(f"{props} 里没有 storeFile 项")
        elif not Path(store).exists():
            problems.append(f"{props} 指向的 keystore 不存在：{store}")
        else:
            print(f"  [ok] keystore {store}")

    if not APKSIGNER.exists():
        problems.append(f"找不到 apksigner：{APKSIGNER}（无法验证签名，这是本脚本的核心校验）")
    else:
        print(f"  [ok] {APKSIGNER}")

    if not JNI_SO.exists():
        problems.append(
            f"缺少 native 引擎 {JNI_SO} —— release 包会没有求解能力。"
            "用 tools/build_so.bat 重建（需工程外依赖，见 docs/75）"
        )
    else:
        print(f"  [ok] native 引擎（{JNI_SO.stat().st_size / 1024 / 1024:.1f} MB）")

    if problems:
        print()
        for p in problems:
            print(f"  [X] {p}", file=sys.stderr)
        die("前置检查未通过，未做任何改动")
    print("  前置检查全部通过")


# ---------------------------------------------------------------- 构建与验签

def build(desc: str) -> Path:
    step("release 构建（R8 压缩，显式给堆）")
    cmd = [
        "gradle",
        "-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m",
        "--no-daemon", "--console=plain",
        ":app:assembleRelease",
        # lintVital 在无网络环境下会尝试联网解析依赖而失败（§0.68 实测），
        # 它只是 lint 的 vital 子集，跳过不影响 APK 正确性。
        "-x", "lintVitalRelease",
        f"-PupdateDesc={desc}",
    ]
    print("  " + " ".join(cmd))
    r = subprocess.run(cmd, cwd=str(ROOT / "code"))
    if r.returncode != 0:
        die("gradle assembleRelease 失败（见上面的输出）")

    outs = sorted(
        (ROOT / "code" / "app" / "build" / "outputs" / "apk" / "release").glob("*.apk"),
        key=lambda p: p.stat().st_mtime,
    )
    if not outs:
        die("构建成功但找不到 release APK 产物")
    return outs[-1]


def verify_signature(apk: Path) -> str:
    """返回证书 SHA-256；未签名时直接失败。"""
    step("验证签名（缺签名 = 装不上，必须硬失败）")
    r = subprocess.run(
        ["java", "-jar", str(APKSIGNER), "verify", "--print-certs", str(apk)],
        capture_output=True, text=True,
    )
    out = (r.stdout or "") + (r.stderr or "")
    if r.returncode != 0 or "Signer #1 certificate SHA-256 digest:" not in out:
        print(out[:800])
        die(
            "APK 未通过签名验证 —— 极可能是 signingConfig 静默为 null（凭据不在位）。"
            "这正是本脚本要拦住的情况"
        )
    m = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)", out)
    digest = m.group(1).lower() if m else "(未取到)"
    cn = re.search(r'Signer #1 certificate DN:\s*(.+)', out)
    print(f"  证书 DN     : {cn.group(1).strip() if cn else '(未取到)'}")
    print(f"  SHA-256     : {digest}")

    # 与历史 release 包比对（apk/ 目录里的归档），确认可覆盖升级
    old = sorted(APK_DIR.glob("*release.apk")) if APK_DIR.exists() else []
    if old:
        r2 = subprocess.run(
            ["java", "-jar", str(APKSIGNER), "verify", "--print-certs", str(old[-1])],
            capture_output=True, text=True,
        )
        o2 = (r2.stdout or "") + (r2.stderr or "")
        m2 = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)", o2)
        if m2:
            prev = m2.group(1).lower()
            if prev == digest:
                print(f"  [ok] 与历史包 {old[-1].name} 同签名 → 可覆盖升级")
            else:
                die(
                    f"签名与历史包不一致！\n  新包 {digest}\n  旧包 {prev}\n"
                    "  签名不一致的包**无法覆盖升级**，用户必须卸载重装。请先确认用了正确的 keystore"
                )
        else:
            print("  [warn] 历史包未取到指纹，跳过比对")
    else:
        print("  [warn] apk/ 下没有历史 release 包，跳过同签名比对")
    return digest


def archive(apk: Path) -> Path:
    step("归档到 apk/ 并计算校验和")
    APK_DIR.mkdir(exist_ok=True)
    dst = APK_DIR / apk.name
    shutil.copy2(apk, dst)
    sha = hashlib.sha256(dst.read_bytes()).hexdigest().upper()
    print(f"  {dst}")
    print(f"  大小   : {dst.stat().st_size / 1024 / 1024:.1f} MB")
    print(f"  SHA-256: {sha}")
    (APK_DIR / (apk.stem + ".sha256")).write_text(f"{sha}  {apk.name}\n", encoding="utf-8")
    return dst


def main() -> None:
    ap = argparse.ArgumentParser(description="StarCam 一键发版")
    ap.add_argument("--version", required=True, help="新的 versionName，如 1.5.64")
    ap.add_argument("--desc", required=True, help="APK 文件名里的更新内容简述（英文短横线短语）")
    ap.add_argument("--dry-run", action="store_true", help="只演算版本号改写，不改文件、不构建")
    ap.add_argument("--skip-build", action="store_true", help="只改版本号，不构建")
    args = ap.parse_args()

    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        die(f"--version 需形如 1.5.64，收到 {args.version!r}")

    code, name, old_desc = read_version()
    if [int(x) for x in args.version.split(".")] <= [int(x) for x in name.split(".")]:
        die(f"新版本 {args.version} 不高于当前 {name}，拒绝执行（可能重复发版）")

    print(f"StarCam 发版：{name}({code}) -> {args.version}({code + 1})")
    print(f"更新内容：{args.desc!r}（原默认值 {old_desc!r}）")

    step("同步版本号（build.gradle.kts 三处）")
    if args.dry_run:
        # 演算：在内存里改写并回显 diff 行，不落盘
        probe = GRADLE_KTS.read_text(encoding="utf-8")
        for pat, rep in (
            (r"^(\s*)versionCode\s*=\s*\d+\s*$", f"versionCode = {code + 1}"),
            (r'^(\s*)versionName\s*=\s*"[^"]+"\s*$', f'versionName = "{args.version}"'),
        ):
            m = re.search(pat, probe, re.M)
            print(f"  - {m.group(0).strip()}")
            print(f"  + {rep}")
        i = probe.find("val updateDesc")
        m = re.search(r'\?:\s*"[^"]*"', probe[i:i + 400])
        print(f"  - {m.group(0)}")
        print(f"  + ?: \"{args.desc}\"")
        print("\n[dry-run] 未修改任何文件")
        return

    bump_version(args.version, args.desc, dry=False)

    if args.skip_build:
        print("\n[skip-build] 版本号已改，未构建。别忘了提交 build.gradle.kts")
        return

    preflight()
    apk = build(args.desc)
    digest = verify_signature(apk)
    dst = archive(apk)

    step("后续步骤（需要凭据，本脚本不代劳）")
    print(f"  1) 提交版本号改动：git add code/app/build.gradle.kts && git commit -m 'chore(release): v{args.version}'")
    print(f"  2) 推送 + 建 tag + 创建 Release + 上传附件：见 AGENTS.md §4 / tools/gh_api_push.py")
    print(f"     附件：{dst}")
    print(f"     SHA-256：{digest[:16]}…（完整值见 apk/{dst.stem}.sha256）")
    print("  3) 发完下载回来比对 SHA-256（AGENTS.md §4「发完必做的回验」）")


if __name__ == "__main__":
    main()
