/* 测试桩：astrometry.net `solver.h` 的最小替身（见本目录 README）。
 * 桥用到的：solver_t 的三个可写字段、MatchObj/tan_t 的 WCS 字段，以及
 * 求解接口（主机测试里全部由桩实现提供）。 */
#ifndef ASTROMETRY_TEST_STUB_SOLVER_H
#define ASTROMETRY_TEST_STUB_SOLVER_H

#include "astrometry/an-bool.h"
#include "astrometry/starxy.h"
#include "astrometry/index.h"

/* 真身还有 crpix/ctype 等字段；桥只读 crval 与 cd。 */
typedef struct {
    double crval[2];
    double cd[2][2];
} tan_t;

typedef struct {
    tan_t wcstan; /* 桥按 mo->wcstan.crval / .cd 取 WCS */
    int indexid;
    int parity;
    int nmatch;
    double logodds;
    double scale; /* arcsec/pixel */
} MatchObj;

typedef struct {
    anbool quit_now; /* 超时置位：solver 在 quad 循环里轮询 */
    double funits_lower;
    double funits_upper;
} solver_t;

solver_t* solver_new(void);
void solver_free(solver_t* sp);
void solver_run(solver_t* sp);
anbool solver_did_solve(const solver_t* s);
MatchObj* solver_get_best_match(solver_t* s);
void solver_set_field(solver_t* s, starxy_t* field);
void solver_set_radec(solver_t* s, double ra, double dec, double radius_deg);
void solver_set_quad_size_fraction(solver_t* s, double lo, double hi);
void solver_add_index(solver_t* s, index_t* idx);

#endif /* ASTROMETRY_TEST_STUB_SOLVER_H */
