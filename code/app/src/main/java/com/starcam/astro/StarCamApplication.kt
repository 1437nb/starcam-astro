package com.starcam.astro

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.starcam.astro.astro.StellarSolverNative

/**
 * 应用入口：在系统内存紧张时归还原生侧索引缓存（§0.67）。
 *
 * 背景：官方求解引擎把 8 档索引（~11MB）缓存在 .so 里供进程生命周期复用，
 * 换来「后续求解零加载成本」。但低端机内存吃紧时这 11MB 无法主动归还，
 * 会加剧 OOM 风险——系统只能杀进程。
 *
 * 这里在 [onTrimMemory] 的 RUNNING_LOW 及以上级别调 [StellarSolverNative.trimIndexCache]，
 * 把索引还给系统；下次求解会重新加载（多一次磁盘读取，可接受）。
 *
 * 原生侧在「发生过超时 detach、可能有线程仍在读索引」时会拒绝释放并返回 0，
 * 所以这里调用是安全的：最坏情况是什么都没释放，不会 use-after-free。
 */
class StarCamApplication : Application() {

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI 不可见 / 后台 / 内存偏低都值得归还；只对「运行中且偏低」以上动作，
        // 避免用户正在连续识别时把索引丢掉导致下次变慢。
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val freed = try {
                StellarSolverNative.trimIndexCache()
            } catch (e: Throwable) {
                0
            }
            if (freed > 0) {
                Log.i(TAG, "内存压力 level=$level，已归还 $freed 档索引缓存")
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private companion object {
        const val TAG = "StarCamApp"
    }
}
