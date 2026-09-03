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

/* ---------------- 超时线程封装 ---------------- */

typedef struct {
    solver_t* sp;
} solve_job_t;

static volatile int solve_done;

static void* solve_thread_fn(void* arg) {
    solve_job_t* job = (solve_job_t*)arg;
    solver_run(job->sp);
    solve_done = 1;
    return NULL;
}

static double mono_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

/* 在独立线程运行 solver_run；超时置 quit_now 并等待线程退出。
 * 返回 0 表示正常结束（可能未解出），1 表示超时中止。 */
static int run_with_timeout(solver_t* sp, double seconds) {
    pthread_t th;
    solve_job_t job;
    job.sp = sp;
    solve_done = 0;
    if (pthread_create(&th, NULL, solve_thread_fn, &job) != 0)
        return -1;
    double deadline = mono_seconds() + seconds;
    while (!solve_done) {
        if (mono_seconds() >= deadline)
            break;
        usleep(20000); /* 20ms 轮询 */
    }
    if (!solve_done) {
        sp->quit_now = TRUE;
        pthread_join(th, NULL);
        return 1;
    }
    pthread_join(th, NULL);
    return 0;
}

/* ---------------- 星点提取（simplexy） ---------------- */

/* simplexy 线程数控制。
 * 2026-08-30 真机实测（v1.3.5，12 张标准集）：arm64 手机上多线程并行时
 * simplexy_run 返回错误、提星 0 颗（官方引擎 7/7 秒败全灭）；服务器 x86
 * 未复现。疑 dsmooth 并行在 arm64 的数据竞争——正确性优先禁用并行，
 * 待专项排查后再启用。 */
static void simplexy_enable_parallel(void) {
    simplexy_set_nthreads(1);
}

/* 返回 starxy_t*（带通量）；失败返回 NULL 并把诊断写入 err_rc/err_peaks。
 * plim_override > 0 时覆盖默认峰值显著度（默认 8；调低→检出更多弱星，
 * 用于真机诊断/弱星场景调优，对应 solve-field 的提星敏感度）。 */
static starxy_t* detect_stars(const float* gray, int w, int h, double plim_override,
                              int* err_rc, int* err_peaks,
                              double* err_gmean, double* err_gmax) {
    simplexy_enable_parallel();
    simplexy_t s;
    memset(&s, 0, sizeof(s));
    s.image = (float*)gray;
    s.nx = w;
    s.ny = h;
    simplexy_set_defaults(&s);
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
    /* 注意：simplexy_run 恒返回 1（源码硬编码，非错误码，2026-08-30 真机+源码
     * 双重验证：x86 复现提出 669 颗星时 rc 仍为 1）。成败只看 npeaks。 */
    if (s.npeaks < 4)
        return NULL;

    starxy_t* field = starxy_new(s.npeaks, TRUE, FALSE);
    if (!field) {
        simplexy_free_contents(&s);
        return NULL;
    }
    for (int i = 0; i < s.npeaks; i++) {
        starxy_set_x(field, i, s.x[i]);
        starxy_set_y(field, i, s.y[i]);
        starxy_set_flux(field, i, s.flux[i]);
    }
    simplexy_free_contents(&s);
    return field;
}

/* ---------------- 索引缓存（进程级） ----------------
 * 历史问题：solver_free 只 pl_free 索引指针列表、不释放 index_t*（所有权在调用方），
 * 旧版每次 solve 都 index_load 8 档（~11MB）且从不释放 = 每次泄漏；且重复磁盘加载
 * 吃掉盲解墙钟预算（§0.15 差距诊断 2）。现按路径缓存 index_t*（进程生命周期），
 * App 进程存活期索引常驻（~11MB），后续求解零加载成本。mutex 保护并发。
 */
static index_t* g_index_cache[16];
static char g_index_paths[16][512];
static int g_index_cache_n = 0;
static pthread_mutex_t g_index_mutex = PTHREAD_MUTEX_INITIALIZER;

static index_t* get_or_load_index(const char* path) {
    int i;
    for (i = 0; i < g_index_cache_n; i++) {
        if (strcmp(g_index_paths[i], path) == 0)
            return g_index_cache[i];
    }
    if (g_index_cache_n >= 16)
        return NULL;
    index_t* idx = index_load(path, 0, NULL);
    if (!idx)
        return NULL;
    strncpy(g_index_paths[g_index_cache_n], path, sizeof(g_index_paths[0]) - 1);
    g_index_cache[g_index_cache_n] = idx;
    g_index_cache_n++;
    return idx;
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

    int timedout = run_with_timeout(sp, time_limit_sec);

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
    } else if (timedout) {
        snprintf(out, outsz,
            "{\"ok\":false,\"error\":\"timeout\",\"nstars\":%d}", field->N);
    } else {
        snprintf(out, outsz,
            "{\"ok\":false,\"error\":\"no-solution\",\"nstars\":%d}", field->N);
    }

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

/* simplexy 提星（兼容历史 extractStars：返回 x/y/flux JSON） */
static jstring extract_stars_impl(JNIEnv* env, jfloatArray gray, jint w, jint h,
                                  jdouble thresholdBgMultiple, jint maxStars) {
    int n = 0;
    float* g = jfloat_array_to_c(env, gray, &n);
    if (!g || n != w * h)
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"bad-input\"}");

    simplexy_t s;
    memset(&s, 0, sizeof(s));
    simplexy_enable_parallel();
    s.image = g;
    s.nx = w;
    s.ny = h;
    simplexy_set_defaults(&s);
    int rc = simplexy_run(&s);

    char* out = NULL;
    if (rc != 0 || s.npeaks <= 0) {
        out = strdup("{\"ok\":false,\"error\":\"extraction-failed\"}");
    } else {
        int k = s.npeaks > maxStars ? maxStars : s.npeaks;
        size_t sz = 128 + k * 64;
        out = (char*)malloc(sz);
        int pos = snprintf(out, sz, "{\"ok\":true,\"n\":%d,\"x\":[", k);
        for (int i = 0; i < k; i++)
            pos += snprintf(out + pos, sz - pos, "%s%.3f", i ? "," : "", s.x[i]);
        pos += snprintf(out + pos, sz - pos, "],\"y\":[");
        for (int i = 0; i < k; i++)
            pos += snprintf(out + pos, sz - pos, "%s%.3f", i ? "," : "", s.y[i]);
        pos += snprintf(out + pos, sz - pos, "],\"flux\":[");
        for (int i = 0; i < k; i++)
            pos += snprintf(out + pos, sz - pos, "%s%.4f", i ? "," : "", s.flux[i]);
        snprintf(out + pos, sz - pos, "]}");
    }
    simplexy_free_contents(&s);
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