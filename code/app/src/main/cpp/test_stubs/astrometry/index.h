/* 测试桩：astrometry.net `index.h` 的最小替身（见本目录 README）。
 * astro_bridge.c 只用到 index_t 的指针与 load/free 两个接口。 */
#ifndef ASTROMETRY_TEST_STUB_INDEX_H
#define ASTROMETRY_TEST_STUB_INDEX_H

/* 真身是一个内含星表树的胖结构；桥只持有指针并在回收时 index_free，
 * 因此桩只需要一个不完整结构体（不能是空定义 —— 桥里有 index_t* 数组）。 */
typedef struct {
    int _stub_unused;
} index_t;

index_t* index_load(const char* name, int flags, index_t* dest);
void index_free(index_t* idx);

#endif /* ASTROMETRY_TEST_STUB_INDEX_H */
