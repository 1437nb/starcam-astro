# 03 · astrometry.net 项目总结 + Android 交叉编译全流程

> 本文回答"了解 astrometry.net 项目"主题：架构、0.97 源码组织、C API、JNI 封装，
> 以及把官方 C 求解器交叉编译成 Android .so 的完整配方（含我们踩过的所有坑）。
> 源码树：`aa:/tmp/wide-test/astrometry.net-0.97/`（解压后改过 3 处，见 §3.6）。

## 3.1 astrometry.net 是什么

- 开源（BSD-3）天文图像**盲求解**（blind astrometric calibration）系统：给定一张星空照片，自动确定
  **拍摄天区（RA/Dec）、比例尺（arcsec/px）、旋转角、奇偶性（是否镜像），并输出 WCS（CD 矩阵 + TAN 投影）**。
- 方法：星点提取（simplexy）→ 星点组成三角形，用"归一化边长比"（对平移/旋转/缩放不变）查预建 quad 索引
  （code K-D tree）→ 投票配对 → 相似变换拟合 → 全星表验证。**我们的 LocalStarMatcher 就是它的简化重实现。**
- 在线服务 nova.astrometry.net；本项目完全本地离线（交叉编译成 .so）。

## 3.2 0.97 源码组织（关键目录）

```
astrometry.net-0.97/
├── solver/       求解核心：solver.c（solver_t API）、engine.c（多索引搜索）、
│                 simplexy.c/h（提星）、quad*.c、startree.c、blind.c、augment-xylist.c、
│                 solve-field.c（命令行）、build-index.c、wcs.c、sip.c、tan.c
├── util/         基础库：qfits-an/（FITS 读取）、libkd/（K-D 树）、gsl-an/（GSL 子集）、
│                 dsmooth.c/.inc（PSF 平滑，我们并行化的地方）、catalog.c、fitsfile.c 等
├── catalogs/     星表索引相关（index-*.fits 生成）
├── bin/          工具：image2pnm 等（多为 Python 脚本包装）
├── demo/         演示图（apod1-5.jpg、m44-*.jpg、index-4119.fits）+ CREDITS（真值！）
├── etc/          astrometry.cfg（索引路径配置）
└── doc/          readme.rst（含每张 demo 图的 --scale-low 参数，即真值线索）
```

## 3.3 求解器架构（工作流）

```
图片 → simplexy（提星：背景中值减除 → dsmooth2 高斯平滑 → σ 估计 → 阈值 → 连通域质心）
     → .axy 星点列表（X/Y/FLUX）
     → engine：按 scale（像素比例尺）猜测 + 索引档（funits_lower/upper）路由
     → solver_t：构建字段 quad（前 N 颗亮星，N 由 --depth 控制）→ code 查询 → 投票 → 验证
     → 输出 WCS（tan_t：CRVAL/CRPIX/CD 矩阵，**CD 按 FITS y-UP 约定**）
```

**0.97 特有的坑**：
- solve-field 的 `-d/--depth` 是"用多少颗亮星"（默认极小 → 0 quads 秒败）；downsample 是 `--downsample`。
- engine 每次只在一个索引档上跑到超时再换（服务器单核上实测 150s 卡 60° 档）。
- `astrometry.cfg` 的 `add_path` 决定索引位置；`inparallel` 开启平行索引查。

## 3.4 C API（solver_t，JNI 用的部分）

```c
solver_t* solver_new();
void      solver_add_index(solver_t*, int indexid, const char* indexfn);  // 二进制索引文件
void      solver_set_field(solver_t*, starxy_t* xy);   // 传入简单提星结果，所有权移交
solver->funits_lower / funits_upper                    // 尺度先验（arcsec/px）
void      solver_set_radec(solver_t*, double ra, double dec, double radius_deg); // 天区先验
solver_run(solver_t*);                                 // 同步求解（可被 quit_now 打断）
int       solver_did_solve(solver_t*);
MatchObj* solver_get_best_match(solver_t*);            // o->scale(arcsec/px), nmatch, logodds, parity, indexid
tan_t*    solver_get_best_wcs(solver_t*);              // wcstan：crval[2]/crpix[2]/cd[2][2]/imagew/imageh/sin
void      solver_free(solver_t*);
// 可写字段：quit_now（超时轮询置位）、set_crpix / set_crpix_center
```

## 3.5 simplexy 提星 API（0.97，替代 SEP）

```c
simplexy_t sp; simplexy_set_defaults(&sp);
sp.image / sp.image_u8 / sp.nx / sp.ny
sp.dpsf=DPSF(1.0) sp.plim=PLIM(8.0) sp.dlim=DLIM(1.0) sp.saddle=SADDLE(5.0)
sp.maxper=1000 sp.maxsize=2000 sp.halfbox=100（网格中值，便宜） sp.maxnpeaks=100000
simplexy_fill_in_defaults(&sp); simplexy_run(&sp);
// 输出：sp.x/sp.y/sp.flux/sp.background/sp.npeaks
```
内部：`nobgsub? → dmedsmooth（网格中值背景）→ dsmooth2/dsmooth2_i16（分离高斯 PSF 平滑，热点）
→ dsigma（σ）→ dmask → dfind2_u8（连通域）→ dallpeaks → flux/background`。

## 3.6 我们对 0.97 源码的改动（交叉编译时同步完成，勿覆盖丢失）

| 改动 | 文件 | 内容 |
|---|---|---|
| ① PSF 平滑多线程 | `util/dsmooth.c` + `util/dsmooth.inc` + `util/simplexy.c/h` | 新增全局 `int an_smooth_parallel`；dsmooth.inc 增加按行(pass1)/按列(pass2)分块的 pthread 线程函数（float/u8/i16 三变体经 SUFFIX 宏特化）；并行路径与串行逐像素等价；`simplexy_set_nthreads(int)` 接口（≤1=官方行为）。**教训：.inc 被 3 次 include → typedef/函数定义只能放 .c；`#undef GLUE` 必须移到函数体尾（宏在调用点展开）。** |
| ② JNI 桥（自写） | `app/src/main/cpp/astro_bridge.c` | simplexy + solver_t 封装：`solve`/`solvePriors`/`extractStars`/`extractStarsE3`；20ms 轮询 `quit_now` 超时（bionic 无 pthread_timedjoin_np）；JSON 输出含 CD 矩阵。 |

## 3.7 Android 交叉编译配方（NDK r26d，目标 arm64-v8a，API 21）

前置：`/opt/ndk`（NDK r26d）、交叉编译 cfitsio 3.47 → `/opt/cross/prefix`。

```bash
export NDK=/opt/ndk
# 1) 环境
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang
AR=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar
RANLIB=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib
export PKG_CONFIG_PATH=/opt/cross/prefix/lib/pkgconfig
# 2) 交叉 cfitsio 3.47（一次）：./configure --host=aarch64-linux-android --prefix=/opt/cross/prefix ...
# 3) 清假产物！源码树曾在 x86_64 上 make 过 → 必须先删，否则 make 报"up to date"假象
find /tmp/wide-test/astrometry.net-0.97 -name '*.o' -delete
find /tmp/wide-test/astrometry.net-0.97 -name '*.a' -delete
# 4) 构建顺序：gsl-an → qfits-an → libkd → util(anbase/anutiles/anfiles) → catalogs → solver
#    （util 有静态库依赖 gsl-an；顺序错会链接失败）
# 5) 产物：
#    qfits-an/libqfits.a  libkd/libkd.a  gsl-an/libgsl-an.a
#    util/liban{base,utils,files}.a  catalogs/libcatalogs.a  solver/libastrometry.a
# 6) 与 astro_bridge.c 一起 → libstellar_solver.so（arm64-v8a，5.09MB）
```

**Bionic 注意事项**：
- 没有独立 libpthread（并入 libc）→ **不要 -lpthread**，编译用 `-pthread` 即可。
- 无 `pthread_timedjoin_np` → 用轮询 + `quit_now`。
- `-ldl` 有告警可忽略。
- 交叉工具链路径注意 llvm-* 在 toolchains/llvm/prebuilt/.../bin/ 下。

**主机工具链注意**：交叉树内不要跑主机 make（.o 架构混了会"假 up-to-date"）；需要 solve-field 等
x86_64 工具时用**另一棵复制树** `/opt/host-an`（本次已删，需要时从 /tmp/wide-test 整棵复制 + 清 .o/.a + 主机 make）。

## 3.8 索引文件（内置 APK + /tmp/wide-test/indexes 同源）

- `index-4112 ~ index-4119.fits` 共 8 档，覆盖视场约 **0.2° ~ 60°+**（quad 直径单位，非视场上限！）。
- APK 内捆绑路径：`StellarSolverNative.ensureIndexes(context)` 解压到 files 目录。
- **视场 180° 上限的论证**（§0.1）：4100 系列标的是 quad（skymark）直径；官方 demo 用 4115~4119 解 45° 视场照片。
- 5200 系列（Gaia，16GB+）更窄更深，不适合手机捆绑。

## 3.9 官方引擎的正确用法速查（验证用）

```bash
# 带天区先验（等价 App 的 solvePriors 路径）：
solve-field --no-plots --overwrite --cpulimit 120 --scale-units degwidth \
  --scale-low 40 --scale-high 80 --ra 78.6 --dec 20.6 --radius 60 -D out photo.jpg
# 盲解（App 无先验路径）：必须给明确 scale 区间 + --depth：
solve-field --no-plots --overwrite --cpulimit 150 --scale-units degwidth \
  --scale-low 3 --scale-high 100 --depth 400 -D out photo.jpg
# 成功后看：
grep -E "Field center|Field size|Field rotation|Field index" *.log
# WCS 在 out/*.wcs；axy 在 out/*.axy（X/Y/FLUX/BACKGROUND 列）；.match / .solved 亦可用
```

## 3.10 官方工具常见故障速查

| 症状 | 原因/解法 |
|---|---|
| `Couldn't find executable "an-pnmtofits"` | PATH 缺 `/tmp/wide-test/astrometry.net-0.97/util`（已固化，勿再犯） |
| `Failed to parse FITS header from file "40"` | engine 参数写错（`-l 40` 不是 log 级别） |
| `You must list at least one index in the config file` | cfg add_path 错（应在 /tmp/wide-test/indexes） |
| `Field did not solve (index index-4119.fits…)` 秒败 | **没传 --depth**（默认只取极少亮星） |
| image2pnm.py `ModuleNotFoundError: astrometry` | PYTHONPATH 缺（已固化） |
| an-fitstopnm Bus error | FITS 文件截断/坏；重新下载并校验 |
| quads 0 tried | depth 不足或 scale 区间不含任何索引档 |