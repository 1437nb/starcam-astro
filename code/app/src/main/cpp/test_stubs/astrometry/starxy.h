/* 测试桩：astrometry.net `starxy.h` 的最小替身（见本目录 README）。
 * 桥只用到 N（星点数）与 x/y/flux 的读写接口。 */
#ifndef ASTROMETRY_TEST_STUB_STARXY_H
#define ASTROMETRY_TEST_STUB_STARXY_H

#include "astrometry/an-bool.h"

typedef struct {
    int N; /* 星点数 —— run_solver 用 field->N 判断"星点够不够" */
} starxy_t;

starxy_t* starxy_new(int n, anbool use_flux, anbool use_background);
void starxy_free(starxy_t* f);
void starxy_set_x(starxy_t* f, int i, double v);
void starxy_set_y(starxy_t* f, int i, double v);
void starxy_set_flux(starxy_t* f, int i, double v);
double starxy_get_x(const starxy_t* f, int i);
double starxy_get_y(const starxy_t* f, int i);
double starxy_get_flux(const starxy_t* f, int i);

#endif /* ASTROMETRY_TEST_STUB_STARXY_H */
