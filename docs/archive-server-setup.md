# 新服务器环境搭建指南

> 目标：在新服务器上还原「构建 + 测试 + 服务器端定标验证」全套能力。
> 参考原服务器：Alibaba Cloud Linux 3，2 核 1.8GB（小内存机器的调优都标注在相应位置）。

## 1. 基础环境

```bash
# JDK 17
yum install -y java-17-openjdk-devel   # 或 apt 等价
export JAVA_HOME=$(ls -d /usr/lib/jvm/java-17-openjdk-* | head -1)

# Gradle 8.12.1
# 下载解压到 /opt/gradle-8.12.1（或改用 gradle wrapper）

# Android SDK
#   sdk.dir 指向安装位置，需 platform android-34 + build-tools（aapt2 用于版本校验）
echo "sdk.dir=/opt/android-sdk" > code/local.properties

# NDK r26d（仅重编 arm64 .so 时需要；日常构建用不到）
# /opt/ndk/android-ndk-r26d/（编译命令见 docs/§0.17/§0.26）

# Python3 + 分析库（服务器端定标验证/工具脚本用）
pip3 install astropy numpy pillow
```

## 2. 构建与测试

```bash
cd code
export JAVA_HOME=...
# 小内存机器（<4GB）务必：
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"
# ≥4GB 机器可简化为 -Xmx2g

gradle --no-daemon --console=plain :app:assembleDebug   # 打包
gradle --no-daemon --console=plain :app:testDebugUnitTest  # 47 项单测
# 产物：app/build/outputs/apk/debug/StarCam-v<版本>-debug.apk（文件名自动带版本）
```

小内存机器注意（踩坑记录，见 docs/§0.11/§0.17/§0.20）：
- gradle.properties 已含 `org.gradle.workers.max=1`、`org.gradle.caching=true`
- **勿加** `android.enableAapt2DaemonMode`（AGP 8 已移除，加即构建失败）
- 编译全量时（触 StarCatalogData.kt）峰值 ~1GB：连续 OOM 就多重试几轮
  （drop caches + sleep 30），或临时停掉高内存宿主进程
- `vm.swappiness=100`、`vm.min_free_kbytes=8192` 有帮助（原服务器已设）
- gradle 测试 worker 与 daemon 并发易 OOM → 备用通道：JUnitCore 直跑
  （classpath 见 docs/§0.11；47/47 与 gradle 等价）

## 3. 服务器端定标验证（可选）

```bash
# solve-field（x86，用于定标对照）：原服务器 /tmp/wide-test/astrometry.net-0.97
# 有编译好的 x86 工具 + 8 档索引；新服务器需重新编译或拷贝该树
# 索引路径：indexes/*.fits（solve-field 的 config add_path 指向此处）
python3 tools/sky50_v2.py          # 生成定标图集（SkyView，含限流重试）
python3 tools/sky50_official_eval2.py  # 官方解 vs 真值评测
```

## 4. 真机测试

见 docs/15-真机与模拟器验证手册.md（adb + AutoTestActivity 全自动）。

## 5. 已知问题速查（详见 docs/§0.26~§0.29）

| 症状 | 处置 |
|---|---|
| 官方解天区明显错误 | logodds 门槛已在（<25 拒），若再现请收集 logcat STARCAM_TEST |
| simplexy 提星 0 颗（APP 内） | 已被跨引擎星点复用绕开；深挖用 testdata/diag 工具 |
| gradle 反复 OOM | 重试循环 + 临时停高内存宿主进程 + 最坏重启客户端 |
| SkyView/维基/WM 不可达 | 换 nova/ GitHub api（raw >1MB 会截断，用 api.github.com Accept 头） |