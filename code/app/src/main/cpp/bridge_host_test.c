/*
 * bridge_host_test.c —— astro_bridge.c 的主机侧（x86_64 Linux）原生测试。
 *
 * 为什么需要（P0-2.6，外部审计 2026-10-06）：
 *   超时 detach 路径此前**无人接盘** —— 代码注释承诺的 leaked_thread_fn 包装
 *   在仓库里并不存在，于是 job、solver 与 g_leaked_threads 全部永久流失：
 *   泄漏计数只增不减，索引回收接口（release_indexes）此后永久拒绝回收 11MB。
 *   本测试**强制走 detach 路径**，断言接盘行为：
 *     ① 正常路径：不标记泄漏、solver 由调用方释放（行为与改动前一致）；
 *     ② 强制让渡：让渡后由 worker 收尾（solver_free 恰好一次、job 归还、
 *        泄漏计数先 +1、worker 退出后归零）；
 *     ③ 索引回收闸门：泄漏线程在飞时拒绝回收（防 UAF），线程结束后放行
 *        （旧实现永久拒绝 —— 本项即审计所指"永久进入不可回收模式"的回归钩子）。
 *
 * 做法：直接 #include 被测源（取得 static 函数的可见性），并为 astrometry.net
 * 的求解 API 提供桩实现 —— 本测试只验证超时/所有权逻辑，不跑真实求解。
 * 注意：桩的签名必须与真实头文件一致（本文件包含的就是官方头文件）。
 *
 * 跑法：tools/run_bridge_host_test.sh（需要 JDK 的 jni.h 与 astrometry 头文件）
 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* ================= 被测源的依赖桩（signature 与官方头文件一致） ================= */

static int g_solver_free_calls = 0;   /* solver_free 被调次数 */
static int g_index_free_calls = 0;    /* index_free 被调次数 */
static double g_fake_run_sec = 0.0;   /* >0：solver_run 睡这么久（模拟不可中断段） */
static int g_fake_did_solve = 0;

/* §0.106：场景 5 的「在飞 UAF」检测装置。
 *   g_fake_index_alive —— 假索引是否仍然存在（index_free 清零）；
 *   g_uaf_watch_index  —— 让 solver_run 在睡眠期间轮询上面这个标记；
 *   g_uaf_observed     —— 睡眠期间观察到索引已被释放（= 发生了 use-after-free）。 */
static volatile int g_fake_index_alive = 0;
static volatile int g_uaf_watch_index = 0;
static volatile int g_uaf_observed = 0;

/* 说明：桩的**声明**来自 astro_bridge.c 里包含的官方头文件（先声明后定义，
 * 定义写在下方 #include 之后）。 */

/* ================= 被测源 ================= */
#include "astro_bridge.c"

/* ================= 桩实现 ================= */

solver_t* solver_new(void) {
    return (solver_t*)calloc(1, sizeof(solver_t));
}

void solver_free(solver_t* sp) {
    g_solver_free_calls++;
    free(sp);
}

void solver_run(solver_t* sp) {
    (void)sp;
    double t = g_fake_run_sec;
    if (t > 0) {
        if (g_uaf_watch_index) {
            /* 场景 5：正常在飞求解。睡眠期间轮询「索引还在不在」——
             * 若期间有人把它释放了，这里就能观测到（真机上表现为段错误）。 */
            double end = mono_seconds() + t;
            while (mono_seconds() < end) {
                if (!g_fake_index_alive) g_uaf_observed = 1;
                usleep(5000);
            }
        } else {
            /* 模拟"卡在不可中断段"：**故意不轮询 quit_now**，让宽限期耗尽、
             * 强制走进让渡分支（这正是审计要求覆盖的路径）。 */
            usleep((useconds_t)(t * 1e6));
        }
    }
}

anbool solver_did_solve(const solver_t* s) {
    (void)s;
    return g_fake_did_solve ? TRUE : FALSE;
}

MatchObj* solver_get_best_match(solver_t* s) {
    (void)s;
    return NULL;
}

void solver_set_field(solver_t* s, starxy_t* field) { (void)s; starxy_free(field); }
void solver_set_radec(solver_t* s, double ra, double dec, double r) {
    (void)s; (void)ra; (void)dec; (void)r;
}
void solver_set_quad_size_fraction(solver_t* s, double a, double b) { (void)s; (void)a; (void)b; }
void solver_add_index(solver_t* s, index_t* idx) { (void)s; (void)idx; }

index_t* index_load(const char* name, int flags, index_t* dest) {
    (void)name; (void)flags;
    return dest; /* 桩：原样返回 dest（调用方传 NULL 时也返回 NULL） */
}

void index_free(index_t* idx) {
    g_index_free_calls++;
    g_fake_index_alive = 0; /* §0.106：在飞检测器据此观测「索引已没了」 */
    free(idx);
}

starxy_t* starxy_new(int n, anbool flux, anbool back) {
    (void)flux; (void)back;
    if (n < 0) return NULL;
    starxy_t* f = (starxy_t*)calloc(1, sizeof(starxy_t) + (size_t)n);
    /* 真实现会填 N（行数）—— 桩必须照做，否则 run_solver 会把外部星点表
     * 当 0 颗（"too-few-stars"），场景 5 根本走不到挂索引那一步。 */
    if (f) f->N = n;
    return f;
}

void starxy_free(starxy_t* f) { free(f); }
void starxy_set_x(starxy_t* f, int i, double v) { (void)f; (void)i; (void)v; }
void starxy_set_y(starxy_t* f, int i, double v) { (void)f; (void)i; (void)v; }
void starxy_set_flux(starxy_t* f, int i, double v) { (void)f; (void)i; (void)v; }
double starxy_get_x(const starxy_t* f, int i) { (void)f; (void)i; return 0.0; }
double starxy_get_y(const starxy_t* f, int i) { (void)f; (void)i; return 0.0; }
double starxy_get_flux(const starxy_t* f, int i) { (void)f; (void)i; return 0.0; }

void simplexy_set_defaults(simplexy_t* s) { memset(s, 0, sizeof(*s)); }
int simplexy_run(simplexy_t* s) { s->npeaks = 0; return 0; }
void simplexy_free_contents(simplexy_t* s) { (void)s; }

/* ================= 断言宏 ================= */

static int g_failures = 0;

#define CHECK(cond, ...)                                                      \
    do {                                                                      \
        if (!(cond)) {                                                        \
            g_failures++;                                                     \
            printf("  ✗ [%s:%d] ", __FILE__, __LINE__);                       \
            printf(__VA_ARGS__);                                              \
            printf("\n");                                                     \
        } else {                                                              \
            printf("  ✓ ");                                                   \
            printf(__VA_ARGS__);                                              \
            printf("\n");                                                     \
        }                                                                     \
    } while (0)

/* 等 worker 收尾：轮询泄漏计数（超时 5s） */
static void wait_leaked_back_to(int target) {
    for (int i = 0; i < 250; i++) {
        if (g_leaked_threads == target) return;
        usleep(20000);
    }
}

/* ================= 场景 ================= */

/* ① 正常路径：solver_run 立刻返回 → 不标记泄漏，solver 由调用方释放 */
static void scenario_normal(void) {
    printf("\n[场景 1] 正常路径（solver_run 立即返回）\n");
    g_fake_run_sec = 0.0;
    g_fake_did_solve = 0;
    g_solver_free_calls = 0;
    int before = g_leaked_threads;

    solver_t* sp = solver_new();
    int leaked = -1;
    int rc = run_with_timeout(sp, 1.0, &leaked, 0);

    CHECK(rc == RUN_OK, "返回码应为 RUN_OK（实际 %d）", rc);
    CHECK(leaked == 0, "不得标记泄漏（实际 %d）", leaked);
    CHECK(g_leaked_threads == before, "泄漏计数不得变化（%d → %d）", before, g_leaked_threads);

    solver_free(sp); /* 模拟 run_solver 尾部：调用方拥有清理权 */
    CHECK(g_solver_free_calls == 1, "solver_free 应恰好一次（实际 %d）", g_solver_free_calls);
}

/* ② 强制让渡：solver_run 忽略 quit_now 且睡过宽限期 → 所有权转给 worker */
static void scenario_forced_detach(void) {
    printf("\n[场景 2] 强制 detach（solver_run 忽略 quit_now，超过宽限期）\n");
    g_fake_run_sec = 1.2;              /* worker 睡 1.2s */
    g_quit_join_grace_sec = 0.2;       /* 宽限期压到 0.2s：必然进入让渡分支 */
    g_solver_free_calls = 0;
    int before = g_leaked_threads;

    solver_t* sp = solver_new();
    int leaked = -1;
    int rc = run_with_timeout(sp, 0.2, &leaked, 0);

    CHECK(rc == RUN_TIMEOUT, "返回码应为 RUN_TIMEOUT（实际 %d）", rc);
    CHECK(leaked == 1, "应标记已让渡（实际 %d）", leaked);
    CHECK(g_leaked_threads == before + 1,
          "让渡后泄漏计数应 +1（%d → %d）", before, g_leaked_threads);
    /* 注意：此刻**不得**碰 sp —— worker 随时会 solver_free（P0-2.6 的调用方约束） */

    wait_leaked_back_to(before);
    CHECK(g_leaked_threads == before,
          "worker 退出后计数应归零（实际 %d）—— 旧实现只增不减", g_leaked_threads);
    CHECK(g_solver_free_calls == 1,
          "让渡路径应由 worker 恰好 solver_free 一次（实际 %d）—— 旧实现零次（永久泄漏）",
          g_solver_free_calls);
}

/* ③ 索引回收闸门：泄漏线程在飞 → 拒绝回收；线程结束 → 放行 */
static void scenario_release_gate(void) {
    printf("\n[场景 3] 索引回收闸门（release_indexes）\n");
    /* 预置一个"已加载"的假索引 */
    pthread_mutex_lock(&g_index_mutex);
    g_index_cache[0] = (index_t*)calloc(1, sizeof(index_t));
    strcpy(g_index_paths[0], "fake-index");
    g_index_cache_n = 1;
    pthread_mutex_unlock(&g_index_mutex);
    g_index_free_calls = 0;

    /* 先起一个泄漏线程（让渡后仍在跑） */
    g_fake_run_sec = 1.0;
    g_quit_join_grace_sec = 0.1;
    solver_t* sp = solver_new();
    int leaked = 0;
    run_with_timeout(sp, 0.1, &leaked, 0);
    CHECK(leaked == 1, "前置条件：应产生一个泄漏线程");

    int freed = release_indexes();
    CHECK(freed == 0 && g_index_free_calls == 0,
          "泄漏线程在飞时**必须拒绝**回收（否则 use-after-free；freed=%d, index_free=%d）",
          freed, g_index_free_calls);

    wait_leaked_back_to(0);
    CHECK(g_leaked_threads == 0, "泄漏线程应已退出（实际计数 %d）", g_leaked_threads);

    freed = release_indexes();
    CHECK(freed == 1 && g_index_free_calls == 1,
          "线程结束后**应能回收**索引（freed=%d, index_free=%d）—— 旧实现永久拒绝",
          freed, g_index_free_calls);
}

/* ④ 让渡后的回归：计数归零后，正常求解仍与场景 1 一致 */
static void scenario_after_detach_regression(void) {
    printf("\n[场景 4] 让渡之后的正常求解（回归）\n");
    g_fake_run_sec = 0.0;
    g_quit_join_grace_sec = 5.0; /* 恢复生产值 */
    g_solver_free_calls = 0;

    solver_t* sp = solver_new();
    int leaked = -1;
    int rc = run_with_timeout(sp, 1.0, &leaked, 0);
    CHECK(rc == RUN_OK && leaked == 0, "让渡后正常路径仍应 RUN_OK/不泄漏");
    solver_free(sp);
    CHECK(g_solver_free_calls == 1, "solver_free 应恰好一次");
}

/* ⑤ §0.106：**正常在飞**求解期间调用 release_indexes() 必须被拒绝
 *    （旧实现只看 g_leaked_threads，正常求解时它是 0 → 当场释放在用索引 → UAF）。
 *    这里走完整的生产路径 run_solver（挂索引 → run_with_timeout），
 *    而不是直接调 run_with_timeout —— 「谁该记账」这件事只有整条路径能测出来。 */
typedef struct {
    const float* gray;
    int w, h;
    const char* paths[1];
    float ext[1 + 3 * 6]; /* 6 颗假外部星：首元素 = 颗数 */
    char out[512];
} run_solver_arg_t;

static void* run_solver_thread(void* p) {
    run_solver_arg_t* a = (run_solver_arg_t*)p;
    run_solver(a->gray, a->w, a->h, a->paths, 1,
               0.0, 0.0, 0.0, 0.0, 0.0, 0, /* 无视场/天区先验 */
               0.0, 5.0,                  /* 默认提星阈值、5s 上限（远大于 0.6s 求解） */
               (int)(1 + 3 * 6), a->ext,
               a->out, sizeof(a->out));
    return NULL;
}

static void wait_active_solvers_back_to(int target) {
    for (int i = 0; i < 500; i++) {
        if (g_active_solvers == target) return;
        usleep(10000);
    }
}

static void scenario_inflight_solve_blocks_release(void) {
    printf("\n[场景 5] 正常在飞求解 vs 索引回收（§0.106：在飞 UAF）\n");
    g_quit_join_grace_sec = 5.0;
    g_fake_run_sec = 0.6;      /* 正常求解，耗时 0.6s（5s 上限内，不超时） */
    g_uaf_watch_index = 1;
    g_uaf_observed = 0;
    g_solver_free_calls = 0;
    g_index_free_calls = 0;

    /* 预置一个"已加载"的假索引，并标记存活 */
    pthread_mutex_lock(&g_index_mutex);
    g_index_cache[0] = (index_t*)calloc(1, sizeof(index_t));
    strcpy(g_index_paths[0], "fake-index-inflight");
    g_index_cache_n = 1;
    pthread_mutex_unlock(&g_index_mutex);
    g_fake_index_alive = 1;

    run_solver_arg_t arg;
    memset(&arg, 0, sizeof(arg));
    arg.w = 64;
    arg.h = 64;
    arg.gray = (const float*)calloc((size_t)arg.w * arg.h, sizeof(float));
    arg.paths[0] = "fake-index-inflight";
    arg.ext[0] = 6.0f; /* 6 颗外部星 → simplexy 桩给 0 颗时走外部星点兜底 */
    for (int i = 0; i < 6; i++) {
        arg.ext[1 + i * 3] = 10.0f + i;
        arg.ext[2 + i * 3] = 12.0f + i;
        arg.ext[3 + i * 3] = 100.0f;
    }

    pthread_t th;
    pthread_create(&th, NULL, run_solver_thread, &arg);
    /* 等它真的进入「索引已挂、正在求解」状态 */
    int waited = 0;
    while (g_active_solvers < 1 && waited < 500) { usleep(5000); waited++; }
    CHECK(g_active_solvers == 1,
          "前置条件：求解在飞时活跃计数应为 1（实际 %d；run_solver 输出：%s）",
          (int)g_active_solvers, arg.out);

    int freed = release_indexes();
    CHECK(freed == 0 && g_index_free_calls == 0,
          "正常在飞求解期间**必须拒绝**回收（旧实现会当场释放在用索引 → UAF；"
          "freed=%d, index_free=%d）", freed, g_index_free_calls);

    pthread_join(th, NULL);
    wait_active_solvers_back_to(0);
    CHECK(g_active_solvers == 0,
          "求解结束后活跃计数应归零（实际 %d）", (int)g_active_solvers);
    /* 在飞检测器放在 join 之后判定：worker 的轮询周期是 5ms，释放后立刻断言会
     * 早于它下一次轮询（实测 uaf=0 的假阴性）；此刻它已跑完整段睡眠，
     * 期间只要索引被释放过就必然被观测到（A/B 实测：旧闸门下 uaf=1）。 */
    CHECK(g_uaf_observed == 0,
          "在飞检测器：整个求解期间索引从未被释放（观测到释放 = use-after-free）");

    freed = release_indexes();
    CHECK(freed == 1 && g_index_free_calls == 1,
          "求解结束后**应能回收**索引（freed=%d, index_free=%d）", freed, g_index_free_calls);

    free((void*)arg.gray);
    g_uaf_watch_index = 0;
    g_fake_run_sec = 0.0;
}

int main(void) {
    printf("=== astro_bridge 主机测试（P0-2.6 超时接盘 + §0.106 在飞回收闸门）===\n");
    scenario_normal();
    scenario_forced_detach();
    scenario_release_gate();
    scenario_after_detach_regression();
    scenario_inflight_solve_blocks_release();
    printf("\n==================================================\n");
    if (g_failures) {
        printf("FAILED：%d 项断言未通过\n", g_failures);
        return 1;
    }
    printf("OK：全部断言通过\n");
    return 0;
}
