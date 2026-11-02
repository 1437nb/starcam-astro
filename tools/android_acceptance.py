#!/usr/bin/env python3
"""Run Android acceptance tests on an explicitly selected device; never uninstall or clear app data.

Build :app:assembleDebug, then :app:assembleDebugAndroidTest in separate calls first.
python tools/android_acceptance.py --serial emulator-5554 --install --ui
Physical devices require --allow-physical; tests create synthetic photos in the test app/gallery.
Reports/screenshots stay under ignored code/app/build/device-lab/.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get("ANDROID_HOME", "C:/dev/android-sdk"))
ADB = SDK / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
PACKAGE = "com.starcam.astro"
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


class Device:
    def __init__(self, serial, directory):
        self.serial, self.directory = serial, directory

    def run(self, *args, timeout=90, binary=False):
        r = subprocess.run([str(ADB), "-s", self.serial, *args], capture_output=True, timeout=timeout)
        if r.returncode:
            raise RuntimeError(r.stderr.decode("utf-8", "replace") or r.stdout.decode("utf-8", "replace"))
        return r.stdout if binary else r.stdout.decode("utf-8", "replace")

    def hierarchy(self):
        # 旋转后 DocumentsUI / Compose 重建无障碍树时，API 28 可能短暂返回空根节点。
        for _ in range(4):
            raw = self.run("exec-out", "uiautomator", "dump", "/dev/tty", timeout=30)
            start, end = raw.find("<?xml"), raw.rfind("</hierarchy>")
            if start >= 0 and end >= 0:
                return ET.fromstring(raw[start:end + len("</hierarchy>")])
            time.sleep(.4)
        raise RuntimeError("UI hierarchy unavailable: " + raw[-160:])

    @staticmethod
    def labels(tree):
        return [n.attrib.get("text", "") or n.attrib.get("content-desc", "") for n in tree.iter("node")]

    def wait(self, *choices, timeout=45):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            tree = self.hierarchy()
            if any(any(choice in label for choice in choices) for label in self.labels(tree)):
                return tree
            time.sleep(.4)
        raise AssertionError(f"UI did not show {choices}")

    def tap(self, *choices):
        tree = self.wait(*choices)
        for node in tree.iter("node"):
            label = node.attrib.get("text", "") or node.attrib.get("content-desc", "")
            if any(choice in label for choice in choices):
                bounds = [int(v) for v in re.findall(r"\d+", node.attrib.get("bounds", ""))]
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    self.run("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                    time.sleep(.35)
                    return
        raise AssertionError(f"No visible target for {choices}")

    def back(self):
        self.run("shell", "input", "keyevent", "4")
        time.sleep(.3)

    def screenshot(self, name):
        target = self.directory / (name + ".png")
        target.write_bytes(self.run("exec-out", "screencap", "-p", binary=True))
        return target.name

    def saved_count(self):
        api = int(self.run("shell", "getprop", "ro.build.version.sdk").strip())
        if api >= 29:
            text = self.run("shell", 'content query --uri content://media/external/images/media --projection _id --where "_display_name LIKE \'StarCam_%\'"')
            return sum(line.startswith("Row:") for line in text.splitlines())
        text = self.run("shell", "ls", "/sdcard/Android/data/com.starcam.astro/files/Pictures/StarCam")
        return sum(line.startswith("StarCam_") for line in text.splitlines())

    def scroll_to(self, *choices):
        for _ in range(5):
            tree = self.hierarchy()
            if any(any(c in label for c in choices) for label in self.labels(tree)):
                return
            size = self.run("shell", "wm", "size")
            width, height = map(int, re.findall(r"(\d+)x(\d+)", size)[-1])
            self.run("shell", "input", "swipe", str(width // 2), str(height * 4 // 5), str(width // 2), str(height // 3), "350")
        raise AssertionError(f"Could not scroll to {choices}")


def ui_smoke(device):
    results = []
    def record(name, action):
        start = time.monotonic()
        try:
            evidence = action()
            results.append(dict(name=name, status="passed", elapsedMs=round((time.monotonic()-start)*1000), evidence=evidence))
        except Exception as e:
            results.append(dict(name=name, status="failed", error=str(e)))
            raise

    settings = {key: device.run("shell", "settings", "get", "system", key).strip() for key in ("font_scale", "accelerometer_rotation", "user_rotation")}
    try:
        # 每轮从首页开始，不清除应用数据；批量返回仍在同一轮内检验。
        device.run("shell", "am", "force-stop", PACKAGE)
        device.run("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        def home():
            device.wait("Take Photo", "拍照认星")
            device.wait("Import Photo", "导入照片")
            return device.screenshot("home")
        record("home-primary-actions", home)
        def importer():
            device.tap("Import Photo", "导入照片")
            # 主入口直接打开系统 DocumentsUI，不再要求先选择文件格式。
            device.wait("Recent", "最近", "Downloads", "下载")
            tree = device.hierarchy()
            if not any("documentsui" in node.attrib.get("package", "") for node in tree.iter("node")):
                raise AssertionError("Unified import did not open the system document picker")
            image = device.screenshot("unified-file-picker")
            device.back()
            device.wait("Take Photo", "拍照认星")
            return image
        record("unified-file-picker-and-return", importer)
        def demo():
            device.tap("Offline Demo", "离线演示")
            device.tap("Random", "随机")
            device.scroll_to("Save Current", "保存当前标注")
            return device.screenshot("result")
        record("demo-full-result", demo)
        def export_dialog():
            device.tap("Save Current", "保存当前标注")
            device.wait("Choose export size", "选择导出尺寸")
            device.wait("Original size", "原图尺寸")
            return device.screenshot("export-size-dialog")
        record("export-size-dialog", export_dialog)
        def landscape():
            device.run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            device.run("shell", "settings", "put", "system", "user_rotation", "1")
            time.sleep(1)
            device.scroll_to("Original size", "原图尺寸")
            return device.screenshot("export-landscape")
        record("landscape-export-dialog", landscape)
        def save_original():
            before = device.saved_count()
            device.tap("Original size", "原图尺寸")
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                if device.saved_count() == before + 1:
                    return device.screenshot("original-saved-from-ui")
                time.sleep(.3)
            raise AssertionError("UI original-size save did not create one output")
        record("original-save-from-ui", save_original)
        device.run("shell", "settings", "put", "system", "user_rotation", "0")
        device.back(); device.back()
        def large_font():
            device.run("shell", "settings", "put", "system", "font_scale", "1.3")
            time.sleep(1)
            device.scroll_to("More methods", "更多方式")
            device.tap("More methods", "更多方式")
            device.wait("More import methods", "更多导入方式")
            image = device.screenshot("import-large-font")
            device.back()
            return image
        record("large-font-import-dialog", large_font)
        def bilingual():
            device.scroll_to("Settings (API Key)", "设置（API Key）")
            device.tap("Settings (API Key)", "设置（API Key）")
            device.tap("Simplified Chinese", "简体中文")
            device.wait("跟随系统")
            image = device.screenshot("settings-chinese")
            device.tap("跟随系统")
            device.wait("Follow System")
            device.back()
            return image
        record("language-switch-chinese-and-system", bilingual)
        def batch_return():
            device.run("shell", "am", "start", "-W", "-n", PACKAGE + "/.DeviceAcceptanceActivity")
            tree = device.wait("Tap a photo", "点击照片查看", timeout=170)
            summary = " ".join(device.labels(tree))
            if "Solved 2" not in summary and "识别成功 2" not in summary:
                raise AssertionError("Expected both public batch photos to solve")
            for name in ("photo4963.jpg", "photo4974.jpg"):
                device.tap(name)
                device.scroll_to("Save Current", "保存当前标注")
                device.back()
                device.wait("Tap a photo", "点击照片查看")
            return device.screenshot("batch-open-return")
        record("batch-open-individually-and-return", batch_return)
    except Exception:
        device.screenshot("ui-failure")
    finally:
        for key, value in settings.items():
            if value == "null": value = "1.0" if key == "font_scale" else "0"
            device.run("shell", "settings", "put", "system", key, value)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--ui", action="store_true")
    parser.add_argument("--allow-physical", action="store_true")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") and not args.allow_physical:
        parser.error("Physical devices require --allow-physical; no device is selected implicitly")
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
    directory = ROOT / "code/app/build/device-lab" / stamp
    directory.mkdir(parents=True, exist_ok=False)
    device = Device(args.serial, directory)
    if device.run("get-state").strip() != "device": raise RuntimeError("Selected device is not ready")
    api = int(device.run("shell", "getprop", "ro.build.version.sdk").strip())
    apk_dir = ROOT / "code/app/build/outputs/apk"
    metadata = json.loads((apk_dir / "debug/output-metadata.json").read_text(encoding="utf-8"))
    app = apk_dir / "debug" / metadata["elements"][0]["outputFile"]
    test = apk_dir / "androidTest/debug/app-debug-androidTest.apk"
    if args.install:
        device.run("install", "-r", str(app), timeout=180)
        device.run("install", "-r", str(test), timeout=180)
    for package, artifact in ((PACKAGE, app), (PACKAGE + ".test", test)):
        installed_path = device.run("shell", "pm", "path", package).strip().removeprefix("package:").splitlines()[0]
        actual = device.run("shell", "sha256sum", installed_path).split()[0]
        if actual != hashlib.sha256(artifact.read_bytes()).hexdigest():
            raise RuntimeError(f"Installed {package} differs from current APK; use --install")
    output = device.run("shell", "am", "instrument", "-w", "-r", PACKAGE + ".test/com.starcam.astro.DeviceAcceptanceInstrumentation", timeout=240)
    (directory / "instrumentation.txt").write_text(output, encoding="utf-8")
    match = re.search(r"DEVICE_ACCEPTANCE_REPORT=([A-Za-z0-9./_-]+)", output)
    if not match or ".." in Path(match[1]).parts: raise RuntimeError("Instrumentation report missing")
    report = json.loads(device.run("exec-out", "run-as", PACKAGE, "cat", "files/" + match[1]))
    report.update(executionKind="emulator" if args.serial.startswith("emulator-") else "physical", api=api,
                  apk=app.name, apkSha256=hashlib.sha256(app.read_bytes()).hexdigest(),
                  testApkSha256=hashlib.sha256(test.read_bytes()).hexdigest(),
                  sourceCommit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip())
    if args.ui: report["ui"] = ui_smoke(device)
    destination = directory / "acceptance.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(destination)
    print(f"Android API {api}: {report['passed']} passed, {report['failed']} failed")
    ui_failed = sum(c["status"] == "failed" for c in report.get("ui", []))
    if args.ui: print(f"UI: {len(report['ui'])-ui_failed} passed, {ui_failed} failed")
    return 1 if report["failed"] or ui_failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
