#!/usr/bin/env bash
# 主机侧原生测试：astro_bridge.c 的超时让渡所有权（P0-2.6）与索引回收闸门（§0.106）。
#
# 依赖：
#   - JDK 的 jni.h（自动从 JAVA_HOME / javac 推断；任何带 JDK 的机器都有）
#   - astrometry.net 头文件：优先用真实源码树（ASTROMETRY_INCLUDE，默认指向构建
#     服务器上的迁移资产）；**没有就回退到仓库内的 test_stubs/**（最小头文件替身，
#     见 code/app/src/main/cpp/test_stubs/README.md）—— CI 走的就是回退路径，
#     因此本测试在任何装了 gcc + JDK 的机器上都能跑，耗时 <1 秒。
#
# 用法：
#   tools/run_bridge_host_test.sh              # 构建 + 运行
#   tools/run_bridge_host_test.sh /tmp/xx      # 指定产物路径
#   ASTROMETRY_INCLUDE=/path/src/include tools/run_bridge_host_test.sh   # 指定真头文件
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/code/app/src/main/cpp"
STUBS="$SRC/test_stubs"
DEFAULT_AN_INC=/opt/starcam-migration-assets/astrometry-local/src/include
OUT=${1:-/tmp/bridge_host_test}

if [ -z "${JAVA_HOME:-}" ]; then
  JAVAC=$(command -v javac || true)
  [ -n "$JAVAC" ] || { echo "找不到 javac，请设置 JAVA_HOME" >&2; exit 1; }
  JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$JAVAC")")")
fi
[ -f "$JAVA_HOME/include/jni.h" ] || { echo "缺 jni.h：$JAVA_HOME/include/jni.h" >&2; exit 1; }

AN_INC=${ASTROMETRY_INCLUDE:-$DEFAULT_AN_INC}
AN_SRC=真实源码树
if [ ! -d "$AN_INC/astrometry" ]; then
  AN_INC="$STUBS"
  AN_SRC=仓库内桩头文件
fi

echo "jni.h      : $JAVA_HOME/include/jni.h"
echo "astrometry : $AN_INC（$AN_SRC）"
echo "产物       : $OUT"

cc -std=gnu11 -O1 -g -Wall -Wextra \
   -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$AN_INC" \
   "$SRC/bridge_host_test.c" -o "$OUT" -lpthread -lm

echo "构建完成，开始运行："
exec "$OUT"
