package com.starcam.astro.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 原生定位辅助类（无外部闭源 SDK 依赖，完全基于 Android 系统 LocationManager）。
 *
 * 优先读取 GPS 硬件定位，辅以网络/基站粗定位与被动定位；
 * 提供快门零延迟的缓存位置获取与后台静默刷新。
 */
class LocationHelper(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    var lastLocation: Location? = null
        private set

    private var isListening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** 检查是否已拥有粗略或精确定位权限 */
    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * 刷新并获取最新可用位置（优先返回已缓存位置，保证拍照零卡顿）。
     */
    @SuppressLint("MissingPermission")
    fun getBestLocation(): Location? {
        if (!hasPermission() || locationManager == null) return null

        val current = lastLocation
        if (current != null && System.currentTimeMillis() - current.time < 120_000) {
            return current
        }

        var best: Location? = current
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider) ?: continue
                    if (best == null || loc.time > best.time) {
                        best = loc
                    }
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
        lastLocation = best
        return best
    }

    /**
     * 开始监听定位变动（在相机开启时调用）。
     * 注意：使用 4 参 requestLocationUpdates 重载（API 1+）；带 Looper 的 5 参
     * 重载是 API 30+ 才有的，minSdk 26 设备会 NoSuchMethodError（v1.5.36 加固）。
     * 调用方在主线程（有 Looper），回调即在主线程执行。
     */
    @SuppressLint("MissingPermission")
    fun startListening() {
        if (!hasPermission() || locationManager == null || isListening) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L,
                    5f,
                    locationListener,
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    10f,
                    locationListener,
                )
            }
            isListening = true
            getBestLocation()
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
    }

    /**
     * 停止定位监听（在相机退出时调用，避免耗电）。
     */
    fun stopListening() {
        if (!isListening || locationManager == null) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Throwable) {
        } finally {
            isListening = false
        }
    }
}
