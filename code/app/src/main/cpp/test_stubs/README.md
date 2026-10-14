# test_stubs —— 主机测试用的 astrometry.net 头文件替身

## 这是什么

`bridge_host_test.c` 直接 `#include "astro_bridge.c"`（为取得 static 函数可见性），
因此需要 `astro_bridge.c` 所包含的 6 个头文件**只有类型与原型**的部分。本目录提供
这些头文件的最小替身，让 C 层状态机测试在**没有 astrometry.net 源码树**的机器上也能
编译运行（CI 就是这种机器）。

## 为什么可以这么做

`bridge_host_test.c` 已经把 astrometry 的**函数**全部桩化了（`solver_*`、`index_*`、
`starxy_*`、`simplexy_*`）。测试验证的是 `astro_bridge.c` 自己的逻辑 —— 超时让渡所有权
（§0.104）、索引回收闸门（§0.106）—— **不跑真实求解**，所以头文件里真正被用到的
只有：结构体的那几个字段、几个宏的取值、以及函数签名。

## 纪律（改这里之前先读）

1. **签名必须与上游一致**：这里的原型要和 astrometry.net（0.97）头文件逐字对齐，
   否则测试编译过得去、真机构建却挂 —— 那正是这类桩最危险的失效方式。
   修改前对照 `/opt/starcam-migration-assets/astrometry-local/src/include/astrometry/`
   （或上游源码树）。
2. **宏的取值抄上游**（如 `SIMPLEXY_DEFAULT_PLIM 8.0`）：桥的默认提星敏感度依赖它。
3. **只放被用到的符号**，不要"顺便补全"。真机构建用的是上游真头文件，
   本目录只服务于主机测试。
4. `run_bridge_host_test.sh` 的优先顺序是：**真实源码树（ASTROMETRY_INCLUDE）→ 本目录**。
   有真头文件时就用真的（能顺带发现签名漂移）；CI 上没有真头文件，走这里。

## 跑法

```bash
bash tools/run_bridge_host_test.sh          # 自动选择头文件来源
ASTROMETRY_INCLUDE=/path/to/astrometry/src/include bash tools/run_bridge_host_test.sh
```
