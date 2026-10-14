/* 测试桩：astrometry.net `simplexy.h` 的最小替身（见本目录 README）。
 * 桥用到的字段：image/nx/ny/plim（输入）、npeaks 与 x/y/flux/background/
 * fluxL/backgroundL/image_u8（输出与回收）。 */
#ifndef ASTROMETRY_TEST_STUB_SIMPLEXY_H
#define ASTROMETRY_TEST_STUB_SIMPLEXY_H

/* 默认参数（取值抄自上游 simplexy.h，勿改：桥的默认提星敏感度依赖 PLIM） */
#define SIMPLEXY_DEFAULT_DPSF        1.0
#define SIMPLEXY_DEFAULT_PLIM        8.0
#define SIMPLEXY_DEFAULT_DLIM        1.0
#define SIMPLEXY_DEFAULT_SADDLE      5.0
#define SIMPLEXY_DEFAULT_MAXPER     1000
#define SIMPLEXY_DEFAULT_MAXSIZE    2000
#define SIMPLEXY_DEFAULT_HALFBOX     100
#define SIMPLEXY_DEFAULT_MAXNPEAKS 100000

typedef struct {
    /* 输入（调用方填） */
    float* image;
    unsigned char* image_u8;
    int nx, ny;
    float plim;
    /* 输出（simplexy_run 填） */
    int npeaks;
    double *x, *y, *flux, *background, *fluxL, *backgroundL;
} simplexy_t;

void simplexy_set_defaults(simplexy_t* s);
int simplexy_run(simplexy_t* s);
void simplexy_free_contents(simplexy_t* s);

#endif /* ASTROMETRY_TEST_STUB_SIMPLEXY_H */
