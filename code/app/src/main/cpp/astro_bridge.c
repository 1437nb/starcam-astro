/*
 * astro_bridge.c — Astrometry.net 0.97 官方求解引擎 Android JNI 桥（纯 C，无 Qt）
 *
 * 组装（全部来自官方 astrometry.net 源码，不重复造轮子）：
 *   - 星点提取：simplexy（官方 solve-field 同款提星器）
 *   - 求解：solver_t C API
 *       · scale 先验：solver->funits_lower/upper（arcsec/pixel）
 *       · 天区先验：solver_set_radec(ra, dec, radius)（官方 engine 同款通道）
 *   - 超时：独立线程跑 solver_run()，超时置 solver->quit_now 中止
 *
 * JNI 导出（与历史 libstellar_solver.so 兼容 + 新增带天区先验的入口）：
 *   Java_com_starcam_astro_astro_StellarSolverNative_extractStars(...)
 *   Java_com_starcam_astro_astro_StellarSolverNative_extractStarsE3(...)
 *   Java_com_starcam_astro_astro_StellarSolverNative_solve(...)
 *   Java_com_starcam_astro_astro_StellarSolverNative_solvePriors(...)
 *   Java_com_starcam_astro_astro_StellarSolverNative_releaseIndexes()   // §0.67
 *   Java_com_starcam_astro_astro_StellarSolverNative_indexCacheStats()  // 诊断
 *
 * 输出 JSON 字段（与 Kotlin parseJson 对齐）：
 *   ok, ra, dec, orient, pixscale, parity, nmatch, indexid, logodds,
 *   cd00, cd01, cd10, cd11（tan_t.wcstan 的 CD 矩阵，度/像素），
 *   fieldw_arcmin, fieldh_arcmin（≈ 边像素 × pixscale 近似）
 */
#include <jni.h>
#include <pthread.h>
#include <time.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <stdio.h>
#include <unistd.h>

#include "astrometry/simplexy.h"
#include "astrometry/solver.h"
#include "astrometry/index.h"
#include "astrometry/sip.h"
#include "astrometry/errors.h"
#include "astrometry/an-bool.h"

#define JSON_BUF 2048
#define ERR_BUF 512

/* ---------------- 超时线程封装 ----------------
 *
 * 并发模型：solver_run 放在 worker 线程里跑，调用线程轮询 + 超时置 quit_now。
 *
 * §0.65 遗留修复：job 与完成标志原先都是**进程级单例**（一个 volatile
 * solve_done + 一个 solve_thread_leaked），正确性依赖「solve 从 JNI 串行进入」
 * 这个隐式前提。现改为**每次调用一份**（堆分配）：
 *   - 多个求解并发时互不干扰；
 *   - 是否泄漏通过 out 参数返回给调用方，不再靠全局标志（旧版下一个请求会把
 *     solve_thread_leaked 清零，从而错误地 free 掉仍在被读取的内存）。
 *
 * g_leaked_threads 统计「已 detach、仍在跑」的线程数，供索引释放接口判断
 * 当前能否安全回收索引（>0 时回收会段错误）。
 */

typedef struct {
    solver_t* sp;
    volatile int done;   /* worker 置 1 表示 solver_run 已返回 */
} solve_job_t;

/* 已 detach 但仍在运行的求解线程数（原子增减）。
 *
 * 局限：线程真正结束我们无从感知（detach 后无法 join），所以这个计数是
 * 「曾经泄漏过的次数」——只会增不会减。索引释放接口据此保守放弃回收：
 * 宁可多占 ~11MB 也不冒 use-after-free 的段错误风险。 */
static volatile int g_leaked_threads = 0;

static void* solve_thread_fn(void* arg) {
    solve_job_t* job = (solve_job_t*)arg;
    solver_run(job->sp);
    job->done = 1;
    return NULL;
}

static double mono_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

/* 在独立线程运行 solver_run；超时置 quit_now 后 join 等它自己退出。
 *
 * 返回 RUN_OK(0) / RUN_TIMEOUT(1) / RUN_SPAWN_FAILED(-1)。
 * 旧版把 -1 也当超时（`else if (timedout)` 里 -1 为真），且 join 无期限等待：
 * 若 quit_now 不被 solver 采纳，超时保护形同虚设。现在 join 有上限，超上限
 * 则分离线程不再等（线程仍在跑，但不再阻塞调用方返回）。
 *
 * [out_leaked] 非空时写入「线程是否已被 detach 泄漏」，调用方据此决定能否
 * solver_free（泄漏时不能，会 use-after-free）。 */
#define RUN_OK 0
#define RUN_TIMEOUT 1
#define RUN_SPAWN_FAILED (-1)
#define QUIT_JOIN_GRACE_SEC 5.0

static int run_with_timeout(solver_t* sp, double seconds, int* out_leaked) {
    pthread_t th;
    if (out_leaked) *out_leaked = 0;
    solve_job_t* job = (solve_job_t*)calloc(1, sizeof(solve_job_t));
    if (!job)
        return RUN_SPAWN_FAILED;
    job->sp = sp;
    if (pthread_create(&th, NULL, solve_thread_fn, job) != 0) {
        free(job);
        return RUN_SPAWN_FAILED;
    }
    double deadline = mono_seconds() + seconds;
    int timedout = 0;
    while (!job->done) {
        if (mono_seconds() >= deadline) {
            timedout = 1;
            break;
        }
        usleep(20000); /* 20ms 轮询 */
    }
    if (timedout) {
        sp->quit_now = TRUE; /* solver 在 quad 循环里轮询此标志 */
        double grace = mono_seconds() + QUIT_JOIN_GRACE_SEC;
        while (!job->done && mono_seconds() < grace)
            usleep(20000);
        if (job->done) {
            pthread_join(th, NULL);
            free(job);
        } else {
            /* quit_now 未被采纳（solver 卡在不可中断段）：不能 join（会永久阻塞），
             * 也不能让调用方 solver_free —— 线程还在读那块内存。
             * job 留给 worker 自己释放（见 leaked_thread_fn 包装）。 */
            __sync_add_and_fetch(&g_leaked_threads, 1);
            if (out_leaked) *out_leaked = 1;
            pthread_detach(th);
        }
        return RUN_TIMEOUT;
    }
    pthread_join(th, NULL);
    free(job);
    return RUN_OK;
}

/* ---------------- 星点提取（simplexy） ---------------- */

/* 注：曾用自打补丁的 simplexy_set_nthreads(1) 禁用 dsmooth 并行——真机 arm64
 * 多线程提星 0 颗疑为数据竞争。现源码树改用上游原版（无该接口），dsmooth
 * 本就串行，无需再调；若将来重新引入并行补丁，此处必须显式禁用。 */

/* 释放 simplexy 的输出，但**不动** s->image。
 * simplexy_free_contents() 会 free(s->image)/free(s->image_u8)，而我们把
 * JNI 的 GetFloatArrayElements 指针（ART 堆，只能由 Release...Elements 归还）
 * 直接挂在 s.image 上——调它等于对 ART 堆做非法 free，真机上表现为
 * 提星后崩溃/结果异常。这里只回收 simplexy 自己 malloc 的那些数组。 */
static void release_simplexy(simplexy_t* s) {
    free(s->x);            s->x = NULL;
    free(s->y);            s->y = NULL;
    free(s->flux);         s->flux = NULL;
    free(s->background);   s->background = NULL;
    free(s->fluxL);        s->fluxL = NULL;
    free(s->backgroundL);  s->backgroundL = NULL;
    s->npeaks = 0;
    /* image/image_u8 的所有权在调用方，此处刻意不置空也不释放 */
}

/* 返回 starxy_t*（带通量）；失败返回 NULL 并把诊断写入 err_rc/err_peaks。
 * plim_override > 0 时覆盖默认峰值显著度（默认 8；调低→检出更多弱星，
 * 用于真机诊断/弱星场景调优，对应 solve-field 的提星敏感度）。 */
static starxy_t* detect_stars(const float* gray, int w, int h, double plim_override,
                              int* err_rc, int* err_peaks,
                              double* err_gmean, double* err_gmax) {
    simplexy_t s;
    /* 顺序要紧：simplexy_set_defaults() 内部是 memset(整个结构体)，
     * 必须在它之后填 image/nx/ny，否则会被清零（真机"提星 0 颗"根因）。 */
    simplexy_set_defaults(&s);
    s.image = (float*)gray;
    s.nx = w;
    s.ny = h;
    if (plim_override > 0.0)
        s.plim = (float)plim_override;
    /* 输入灰度统计（诊断：全 0/极低 → 输入管线问题；正常 → simplexy 内部问题） */
    double gsum = 0; float gmax = 0;
    for (long i = 0; i < (long)w * h; i++) {
        gsum += gray[i];
        if (gray[i] > gmax) gmax = gray[i];
    }
    *err_gmean = (w * h) > 0 ? gsum / ((long)w * h) : 0;
    *err_gmax = gmax;
    int rc = simplexy_run(&s);
    *err_rc = rc;
    *err_peaks = (int)s.npeaks;
    /* rc=0 只表示 dmask 没找到任何超阈值像素（即真的一颗都没有），
     * 所以 rc 可作辅助诊断；但成败判定以 npeaks 为准。 */
    if (s.npeaks < 4) {
        release_simplexy(&s);
        return NULL;
    }

    starxy_t* field = starxy_new(s.npeaks, TRUE, FALSE);
    if (!field) {
        release_simplexy(&s);
        return NULL;
    }
    for (int i = 0; i < s.npeaks; i++) {
        starxy_set_x(field, i, s.x[i]);
        starxy_set_y(field, i, s.y[i]);
        starxy_set_flux(field, i, s.flux[i]);
    }
    release_simplexy(&s);
    return field;
}

/* ---------------- 索引缓存（进程级） ----------------
 * 历史问题：solver_free 只 pl_free 索引指针列表、不释放 index_t*（所有权在调用方），
 * 旧版每次 solve 都 index_load 8 档（~11MB）且从不释放 = 每次泄漏；且重复磁盘加载
 * 吃掉盲解墙钟预算（§0.15 差距诊断 2）。现按路径缓存 index_t*（进程生命周期），
 * App 进程存活期索引常驻（~11MB），后续求解零加载成本。mutex 保护并发。
 *
 * §0.67：新增 release_indexes() 让系统内存紧张时能主动归还这 11MB。
 * 释放前必须确认没有 solver 线程正在使用索引 —— 若发生过超时 detach
 * （g_leaked_threads > 0），那些线程可能仍在读索引，此时回收会段错误：
 * 一律拒绝释放，宁可多占内存也不崩。
 */
static index_t* g_index_cache[16];
static char g_index_paths[16][512];
static int g_index_cache_n = 0;
/* 缓存未命中（含被 release 清空后重建）的累计次数，供诊断用。 */
static int g_index_load_count = 0;
static pthread_mutex_t g_index_mutex = PTHREAD_MUTEX_INITIALIZER;

static index_t* get_or_load_index(const char* path) {
    int i;
    for (i = 0; i < g_index_cache_n; i++) {
        if (strcmp(g_index_paths[i], path) == 0)
            return g_index_cache[i];
    }
    if (g_index_cache_n >= 16) {
        /* 不再静默返回 NULL：调用方只能看到 no-index-loaded，无法区分
         * 「没打包索引」和「缓存满」。 */
        fprintf(stderr, "[astro_bridge] index cache full (%d), refusing %s\n",
                g_index_cache_n, path);
        return NULL;
    }
    index_t* idx = index_load(path, 0, NULL);
    if (!idx)
        return NULL;
    strncpy(g_index_paths[g_index_cache_n], path, sizeof(g_index_paths[0]) - 1);
    g_index_cache[g_index_cache_n] = idx;
    g_index_cache_n++;
    g_index_load_count++;
    return idx;
}

/* 归还全部索引内存。返回实际释放的档数；0 表示未释放（不满足安全条件）。
 *
 * 安全性：持锁检查 g_leaked_threads —— 有 detach 线程在跑就不动
 * （那些线程可能正访问索引）。线程数无从观测何时归零，故泄漏过就永久拒绝，
 * 保守但绝不 use-after-free。 */
static int release_indexes(void) {
    int freed = 0;
    pthread_mutex_lock(&g_index_mutex);
    if (g_leaked_threads == 0) {
        for (int i = 0; i < g_index_cache_n; i++) {
            if (g_index_cache[i]) {
                index_free(g_index_cache[i]); /* 官方提供的释放接口 */
                g_index_cache[i] = NULL;
                g_index_paths[i][0] = '\0';
                freed++;
            }
        }
        g_index_cache_n = 0;
    }
    pthread_mutex_unlock(&g_index_mutex);
    return freed;
}

/* ---------------- 求解核心（两个 JNI 入口共用） ---------------- */

static void run_solver(
    const float* gray, int w, int h,
    const char* const* index_paths, int n_index_paths,
    double fov_lo_deg, double fov_hi_deg,
    double ra_deg, double dec_deg, double radius_deg, int use_radec,
    double plim_override, double time_limit_sec,
    const float* ext_stars,
    char* out, size_t outsz) {

    out[0] = '\0';
    snprintf(out, outsz, "{\"ok\":false,\"error\":\"init\"}");

    /* 星点来源优先级：外部星点（Kotlin 侧 SEP 提星，坐标 0 起 y 向下，与
     * simplexy 约定一致）→ simplexy。
     * 2026-08-30 真机：部分机型 ROM 上 .so 内 simplexy 提星 0 颗
     * （rc=0 nstars=0，独立 arm64 可执行同算法却提出 462 颗——调用上下文
     * 相关），用跨引擎星点复用绕开。 */
    starxy_t* field = NULL;
    int ext_n = 0;
    if (ext_stars) {
        ext_n = (int)ext_stars[0];
        if (ext_n >= 4) {
            field = starxy_new(ext_n, TRUE, FALSE);
            for (int i = 0; i < ext_n; i++) {
                starxy_set_x(field, i, ext_stars[1 + i*3]);
                starxy_set_y(field, i, ext_stars[2 + i*3]);
                starxy_set_flux(field, i, ext_stars[3 + i*3]);
            }
            snprintf(out, outsz, "{\"ok\":false,\"error\":\"ext-stars-loaded\"}");
        }
    }
    int ext_rc = -1, ext_peaks = -1;
    double ext_gmean = 0, ext_gmax = 0;
    if (!field) {
        field = detect_stars(gray, w, h, plim_override, &ext_rc, &ext_peaks,
                             &ext_gmean, &ext_gmax);
    }
    if (!field) {
        snprintf(out, outsz,
            "{\"ok\":false,\"error\":\"star-extraction-failed\",\"nstars\":%d,\"rc\":%d,"
            "\"gmean\":%.2f,\"gmax\":%.1f}",
            ext_peaks, ext_rc, ext_gmean, ext_gmax);
        return;
    }
    if (field->N < 4) {
        snprintf(out, outsz,
            "{\"ok\":false,\"error\":\"too-few-stars\",\"nstars\":%d}", field->N);
        starxy_free(field);
        return;
    }

    solver_t* sp = solver_new();
    if (!sp) {
        snprintf(out, outsz,
            "{\"ok\":false,\"error\":\"solver-new-failed\",\"nstars\":%d}", field->N);
        starxy_free(field);
        return;
    }

    /* scale 先验：视场宽度（度）→ arcsec/pixel（图像宽度方向） */
    double lo = (fov_lo_deg > 0.0) ? fov_lo_deg * 3600.0 / w : 0.0;
    double hi = (fov_hi_deg > 0.0) ? fov_hi_deg * 3600.0 / w : 0.0;
    /* 无先验时交给 solver 自身全范围（引擎默认 0.1°~180°） */
    if (lo > 0.0) sp->funits_lower = lo;
    if (hi > 0.0) sp->funits_upper = hi;

    /* 天区先验 */
    if (use_radec)
        solver_set_radec(sp, ra_deg, dec_deg, radius_deg);

    /* 与 solve-field 一致的 quad 尺寸范围 */
    solver_set_quad_size_fraction(sp, 0.1, 1.0);

    /* 加载索引（进程级缓存：重复调用零加载成本） */
    int nidx = 0;
    pthread_mutex_lock(&g_index_mutex);
    for (int i = 0; i < n_index_paths; i++) {
        index_t* idx = get_or_load_index(index_paths[i]);
        if (idx) {
            solver_add_index(sp, idx);
            nidx++;
        }
    }
    pthread_mutex_unlock(&g_index_mutex);
    if (nidx == 0) {
        snprintf(out, outsz,
            "{\"ok\":false,\"error\":\"no-index-loaded\",\"nstars\":%d}", field->N);
        solver_free(sp);
        starxy_free(field);
        return;
    }

    solver_set_field(sp, field); /* 所有权转移 */

    int leaked = 0;
    int timedout = run_with_timeout(sp, time_limit_sec, &leaked);

    if (solver_did_solve(sp)) {
        MatchObj* mo = solver_get_best_match(sp);
        tan_t* wcs = &(mo->wcstan);
        double orient = atan2(wcs->cd[1][0], wcs->cd[0][0]) * 180.0 / M_PI;
        double pixscale = mo->scale; /* arcsec/pixel */
        snprintf(out, outsz,
            "{\"ok\":true,"
            "\"ra\":%.8f,\"dec\":%.8f,"
            "\"orient\":%.6f,\"pixscale\":%.6f,\"parity\":%d,"
            "\"nmatch\":%d,\"nstars\":%d,\"indexid\":%d,\"logodds\":%.4f,"
            "\"cd00\":%.10e,\"cd01\":%.10e,\"cd10\":%.10e,\"cd11\":%.10e,"
            "\"fieldw_arcmin\":%.4f,\"fieldh_arcmin\":%.4f}",
            wcs->crval[0], wcs->crval[1],
            orient, pixscale, mo->parity ? 1 : 0,
            mo->nmatch, field->N, mo->indexid, mo->logodds,
            wcs->cd[0][0], wcs->cd[0][1], wcs->cd[1][0], wcs->cd[1][1],
            w * pixscale / 60.0, h * pixscale / 60.0);
    } else if (timedout == RUN_TIMEOUT) {
        snprintf(out, outsz,
            "{\"ok\":false,\"error\":\"timeout\",\"nstars\":%d}", field->N);
    } else if (timedout == RUN_SPAWN_FAILED) {
        snprintf(out, outsz,
            "{\"ok\":false,\"error\":\"spawn-failed\",\"nstars\":%d}", field->N);
    } else {
        snprintf(out, outsz,
            "{\"ok\":false,\"error\":\"no-solution\",\"nstars\":%d}", field->N);
    }

    /* 线程已 detach 说明 solver_run 仍在跑（quit_now 未被采纳），此时
     * solver_free 会释放它正在读的索引/星表内存 → 段错误。宁可泄漏
     * 一次 solver（约百 KB + 共享索引），也不能崩在用户手机上。
     * 注意判据来自本次调用的 out 参数，不再是全局标志——并发/连续求解时
     * 全局标志会被下一次调用清零，导致这里错误地 free（§0.65 遗留）。 */
    if (leaked)
        return;
    solver_free(sp);
}

/* ---------------- JNI 工具 ---------------- */

static float* jfloat_array_to_c(JNIEnv* env, jfloatArray arr, int* n) {
    if (!arr) return NULL;
    *n = (*env)->GetArrayLength(env, arr);
    if (*n <= 0) return NULL;
    return (*env)->GetFloatArrayElements(env, arr, NULL);
}

static int jstring_array_to_c(JNIEnv* env, jobjectArray arr, char*** out) {
    int n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    if (n <= 0) { *out = NULL; return 0; }
    char** paths = (char**)calloc(n, sizeof(char*));
    for (int i = 0; i < n; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char* cs = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
        paths[i] = cs ? strdup(cs) : NULL;
        if (js && cs) (*env)->ReleaseStringUTFChars(env, js, cs);
    }
    *out = paths;
    return n;
}

static void free_string_array(char** arr, int n) {
    if (!arr) return;
    for (int i = 0; i < n; i++) free(arr[i]);
    free(arr);
}

/* ---------------- JNI 导出 ---------------- */

JNIEXPORT jstring JNICALL
Java_com_starcam_astro_astro_StellarSolverNative_solve(
    JNIEnv* env, jclass cls,
    jfloatArray gray, jint w, jint h,
    jobjectArray indexPaths,
    jdouble fovLoDeg, jdouble fovHiDeg, jdouble plimOverride, jdouble timeLimitSec,
    jfloatArray extStars) {

    int n = 0;
    float* g = jfloat_array_to_c(env, gray, &n);
    if (!g || n != w * h) {
        if (g) (*env)->ReleaseFloatArrayElements(env, gray, g, JNI_ABORT);
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"bad-input\"}");
    }
    char** paths = NULL;
    int np = jstring_array_to_c(env, indexPaths, &paths);

    int en = 0;
    float* es = jfloat_array_to_c(env, extStars, &en);
    char out[JSON_BUF];
    run_solver(g, w, h, (const char* const*)paths, np,
               fovLoDeg, fovHiDeg, 0.0, 0.0, 0.0, 0, plimOverride, timeLimitSec,
               es, out, sizeof(out));
    if (es) (*env)->ReleaseFloatArrayElements(env, extStars, es, JNI_ABORT);

    (*env)->ReleaseFloatArrayElements(env, gray, g, JNI_ABORT);
    free_string_array(paths, np);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_starcam_astro_astro_StellarSolverNative_solvePriors(
    JNIEnv* env, jclass cls,
    jfloatArray gray, jint w, jint h,
    jobjectArray indexPaths,
    jdouble fovLoDeg, jdouble fovHiDeg,
    jdouble raDeg, jdouble decDeg, jdouble radiusDeg,
    jdouble plimOverride, jdouble timeLimitSec,
    jfloatArray extStars) {

    int n = 0;
    float* g = jfloat_array_to_c(env, gray, &n);
    if (!g || n != w * h) {
        if (g) (*env)->ReleaseFloatArrayElements(env, gray, g, JNI_ABORT);
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"bad-input\"}");
    }
    char** paths = NULL;
    int np = jstring_array_to_c(env, indexPaths, &paths);

    int use_radec = (raDeg > -998.0 && decDeg > -998.0 && radiusDeg > 0.0) ? 1 : 0;

    int en2 = 0;
    float* es2 = jfloat_array_to_c(env, extStars, &en2);
    char out[JSON_BUF];
    run_solver(g, w, h, (const char* const*)paths, np,
               fovLoDeg, fovHiDeg,
               raDeg, decDeg, radiusDeg, use_radec,
               plimOverride, timeLimitSec, es2, out, sizeof(out));
    if (es2) (*env)->ReleaseFloatArrayElements(env, extStars, es2, JNI_ABORT);

    (*env)->ReleaseFloatArrayElements(env, gray, g, JNI_ABORT);
    free_string_array(paths, np);
    return (*env)->NewStringUTF(env, out);
}

/* 按 flux 降序取前 k 个（k < n 时的部分选择；simplexy 输出是扫描序，
 * 直接截断会留下图像上半部分的星而丢掉亮星）。 */
static void top_k_by_flux(const starxy_t* f, int k, int* out_idx) {
    int n = f->N;
    char* used = (char*)calloc(n, 1);
    for (int i = 0; i < k; i++) {
        int best = -1;
        double bestf = -1e300;
        for (int j = 0; j < n; j++) {
            if (used[j]) continue;
            double fj = starxy_get_flux(f, j);
            if (fj > bestf) { bestf = fj; best = j; }
        }
        used[best] = 1;
        out_idx[i] = best;
    }
    free(used);
}

/* simplexy 提星（兼容历史 extractStars：返回 x/y/flux JSON） */
static jstring extract_stars_impl(JNIEnv* env, jfloatArray gray, jint w, jint h,
                                  jdouble thresholdBgMultiple, jint maxStars) {
    int n = 0;
    float* g = jfloat_array_to_c(env, gray, &n);
    if (!g)
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"bad-input\"}");
    if (n != (int)(w * h)) {
        (*env)->ReleaseFloatArrayElements(env, gray, g, JNI_ABORT);
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"bad-input\"}");
    }

    simplexy_t s;
    /* 顺序要紧：set_defaults 是 memset，必须先调再填 image/nx/ny。 */
    simplexy_set_defaults(&s);
    s.image = g;
    s.nx = w;
    s.ny = h;
    /* 阈值：调用方给的是「背景 sigma 倍数」，直接映射 plim（默认 8.0） */
    if (thresholdBgMultiple > 0.0)
        s.plim = (float)thresholdBgMultiple;
    /* rc=0 表示 dmask 未标记任何超阈值像素（真无星）；成败以 npeaks 为准。
     * 旧代码用 `rc != 0 || npeaks <= 0` 判失败，语义正好相反。 */
    simplexy_run(&s);

    char* out = NULL;
    if (s.npeaks <= 0) {
        out = strdup("{\"ok\":false,\"error\":\"extraction-failed\"}");
    } else {
        starxy_t* f = starxy_new(s.npeaks, TRUE, FALSE);
        if (!f) {
            out = strdup("{\"ok\":false,\"error\":\"oom\"}");
        } else {
            for (int i = 0; i < s.npeaks; i++) {
                starxy_set_x(f, i, s.x[i]);
                starxy_set_y(f, i, s.y[i]);
                starxy_set_flux(f, i, s.flux[i]);
            }
            int k = s.npeaks > maxStars ? maxStars : s.npeaks;
            int* idx = (int*)malloc(sizeof(int) * (k > 0 ? k : 1));
            if (k == s.npeaks) {
                for (int i = 0; i < k; i++) idx[i] = i;
            } else {
                top_k_by_flux(f, k, idx);
            }
            size_t sz = 160 + (size_t)k * 64;
            out = (char*)malloc(sz);
            int pos = snprintf(out, sz, "{\"ok\":true,\"n\":%d,\"x\":[", k);
            for (int i = 0; i < k; i++)
                pos += snprintf(out + pos, sz - pos, "%s%.3f", i ? "," : "",
                                starxy_get_x(f, idx[i]));
            pos += snprintf(out + pos, sz - pos, "],\"y\":[");
            for (int i = 0; i < k; i++)
                pos += snprintf(out + pos, sz - pos, "%s%.3f", i ? "," : "",
                                starxy_get_y(f, idx[i]));
            pos += snprintf(out + pos, sz - pos, "],\"flux\":[");
            for (int i = 0; i < k; i++)
                pos += snprintf(out + pos, sz - pos, "%s%.4f", i ? "," : "",
                                starxy_get_flux(f, idx[i]));
            snprintf(out + pos, sz - pos, "]}");
            free(idx);
            starxy_free(f);
        }
    }
    release_simplexy(&s);
    (*env)->ReleaseFloatArrayElements(env, gray, g, JNI_ABORT);

    jstring js = (*env)->NewStringUTF(env, out);
    free(out);
    return js;
}

JNIEXPORT jstring JNICALL
Java_com_starcam_astro_astro_StellarSolverNative_extractStars(
    JNIEnv* env, jclass cls, jfloatArray gray, jint w, jint h,
    jdouble thresholdBgMultiple, jint maxStars) {
    return extract_stars_impl(env, gray, w, h, thresholdBgMultiple, maxStars);
}

JNIEXPORT jstring JNICALL
Java_com_starcam_astro_astro_StellarSolverNative_extractStarsE3(
    JNIEnv* env, jclass cls, jfloatArray gray, jint w, jint h,
    jdouble thresholdBgMultiple, jint maxStars) {
    return extract_stars_impl(env, gray, w, h, thresholdBgMultiple, maxStars);
}

/* 归还索引内存（§0.67）。返回释放的档数；0 = 未释放（有 detach 线程在使用，
 * 或本来就没加载）。调用方（Kotlin onTrimMemory）只在系统内存紧张时调，
 * 释放后下次求解会重新加载（首次慢一次，可接受）。 */
JNIEXPORT jint JNICALL
Java_com_starcam_astro_astro_StellarSolverNative_releaseIndexes(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    return (jint)release_indexes();
}

/* 诊断：索引进程缓存状态（已加载档数 / 累计加载次数 / 泄漏线程数）。
 * 供 debug 构建排查「索引相关」问题时读取。 */
JNIEXPORT jstring JNICALL
Java_com_starcam_astro_astro_StellarSolverNative_indexCacheStats(JNIEnv* env, jclass cls) {
    char buf[192];
    pthread_mutex_lock(&g_index_mutex);
    int n = g_index_cache_n, loads = g_index_load_count;
    pthread_mutex_unlock(&g_index_mutex);
    snprintf(buf, sizeof(buf),
             "{\"cached\":%d,\"loads\":%d,\"leakedThreads\":%d}", n, loads,
             (int)g_leaked_threads);
    return (*env)->NewStringUTF(env, buf);
}