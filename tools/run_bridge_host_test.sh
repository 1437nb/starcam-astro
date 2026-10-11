#!/usr/bin/env bash
# 主机侧原生测试：astro_bridge.c 的超时 detach 所有权接盘（P0-2.6）。
#
# 工程外依赖：JDK 的 jni.h（自动从 JAVA_HOME / javac 推断）、astrometry.net 头文件
# （默认指向构建服务器上的迁移资产；换机时用环境变量 ASTROMETRY_INCLUDE 覆盖）。
#
# 用法：
#   tools/run_bridge_host_test.sh              # 构建 + 运行
#   tools/run_bridge_host_test.sh /tmp/xx      # 指定产物路径
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/code/app/src/main/cpp"
AN_INC=${ASTROMETRY_INCLUDE:-/opt/starcam-migration-assets/astrometry-local/src/include}
OUT=${1:-/tmp/bridge_host_test}

if [ -z "${JAVA_HOME:-}" ]; then
  JAVAC=$(command -v javac || true)
  [ -n "$JAVAC" ] || { echo "找不到 javac，请设置 JAVA_HOME" >&2; exit 1; }
  JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$JAVAC")")")
fi

[ -f "$JAVA_HOME/include/jni.h" ] || { echo "缺 jni.h：$JAVA_HOME/include/jni.h" >&2; exit 1; }
[ -d "$AN_INC/astrometry" ] || { echo "缺 astrometry 头文件目录：$AN_INC/astrometry" >&2; exit 1; }

echo "jni.h      : $JAVA_HOME/include/jni.h"
echo "astrometry : $AN_INC"
echo "产物       : $OUT"

cc -std=gnu11 -O1 -g -Wall -Wextra \
   -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$AN_INC" \
   "$SRC/bridge_host_test.c" -o "$OUT" -lpthread -lm

echo "构建完成，开始运行："
exec "$OUT"
