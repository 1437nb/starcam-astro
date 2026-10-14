/* 测试桩：astrometry.net `an-bool.h` 的最小替身（见本目录 README）。
 * 只在 `bridge_host_test.c`（主机侧 C 测试）里使用，不进 Android 构建。 */
#ifndef ASTROMETRY_TEST_STUB_AN_BOOL_H
#define ASTROMETRY_TEST_STUB_AN_BOOL_H

typedef int anbool;

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif

#endif /* ASTROMETRY_TEST_STUB_AN_BOOL_H */
