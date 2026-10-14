#!/usr/bin/env bash
# 自适应等待远端（验证机）上的一次"丢出去就跑"的构建/测试收尾。
#
# 为什么需要它：验证机上的 gradle 跑一次是 5~40 分钟，且**必须 detach**（setsid nohup）
# —— 直接挂在前台 ssh 上，会话一断就把 gradle 杀在半路，两个半截实例并存还会 OOM
# （2026-10-11 实测）。于是启动与等待分离：启动一条命令丢后台，本脚本按
# 「日志出现收尾标志」自适应轮询，**出现即返回**（不是固定睡死等），超时也返回。
#
# 用法：
#   tools/wait_remote_run.sh <ssh 目标> <日志路径> [超时秒=3600] [收尾正则]
# 例：
#   tools/wait_remote_run.sh ec2-hk /tmp/full.log            # 等 BUILD SUCCESSFUL|FAILED
#   tools/wait_remote_run.sh ec2-hk /tmp/x.log 600 'tests='  # 自定义收尾标志
#
# 退出码：0=看到收尾标志；2=超时（远端任务多半还在跑，可再来一次）
set -euo pipefail

HOST=${1:?用法: wait_remote_run.sh <ssh 目标> <日志路径> [超时秒] [收尾正则]}
LOG=${2:?用法: wait_remote_run.sh <ssh 目标> <日志路径> [超时秒] [收尾正则]}
TIMEOUT=${3:-3600}
PATTERN=${4:-'BUILD (SUCCESSFUL|FAILED)'}
INTERVAL=10

deadline=$(( $(date +%s) + TIMEOUT ))
last=""
while :; do
  # 每次只取日志尾部：够判断收尾标志，也不会把整个日志灌进上下文
  last=$(ssh -o ConnectTimeout=15 "$HOST" "tail -c 6000 '$LOG' 2>/dev/null" 2>/dev/null || true)
  if [ -n "$last" ] && printf '%s' "$last" | grep -Eq "$PATTERN"; then
    echo "=== 收尾标志出现（$(date +%H:%M:%S)）==="
    printf '%s\n' "$last" | tail -n 6
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "=== 等待超时（${TIMEOUT}s；远端任务可能仍在跑）$(date +%H:%M:%S) ===" >&2
    printf '%s\n' "$last" | tail -n 6 >&2
    exit 2
  fi
  sleep "$INTERVAL"
done
