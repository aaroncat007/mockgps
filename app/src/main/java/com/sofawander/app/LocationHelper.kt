package com.sofawander.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

object LocationHelper {
    private const val TAG = "LocationHelper"

    /**
     * 同步取得最佳可用位置 (用於 Service、無 UI 等需要立即決定起點的情境)
     */
    @SuppressLint("MissingPermission")
    fun getBestAvailableLocationSync(context: Context, fallbackLat: Double = 0.0, fallbackLng: Double = 0.0): Pair<Double, Double> {
        val prefs = context.getSharedPreferences("mock_prefs", Context.MODE_PRIVATE)
        val isMockingActive = prefs.getBoolean("mock_service_running", false)
        if (isMockingActive) {
            Log.d(TAG, "Mocking is active, bypassing sync GPS fetch and directly returning fallback/mock coords.")
            return Pair(fallbackLat, fallbackLng)
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        try {
            // 先嘗試取得 GPS 的最後位置 (通常精確度最高)
            val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (gpsLoc != null && !isInvalidLocation(gpsLoc.latitude, gpsLoc.longitude)) {
                return Pair(gpsLoc.latitude, gpsLoc.longitude)
            }

            // 若無 GPS，退回嘗試網路定位
            val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (netLoc != null && !isInvalidLocation(netLoc.latitude, netLoc.longitude)) {
                return Pair(netLoc.latitude, netLoc.longitude)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing location permissions during sync location fetch: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Exception during sync location fetch: ${e.message}")
        }

        // 最後退回使用傳入的 fallback 值 (例如最後紀錄的 mock 座標)
        return Pair(fallbackLat, fallbackLng)
    }

    /**
     * 非同步取得當前精確位置 (用於 UI，如 MainActivity 點擊定位按鈕)
     */
    @SuppressLint("MissingPermission")
    fun fetchCurrentLocationAsync(
        context: Context,
        fallbackLat: Double = 0.0,
        fallbackLng: Double = 0.0,
        onSuccess: (Double, Double) -> Unit,
        onFailure: () -> Unit
    ) {
        val prefs = context.getSharedPreferences("mock_prefs", Context.MODE_PRIVATE)
        val isMockingActive = prefs.getBoolean("mock_service_running", false)
        if (isMockingActive) {
            Log.d(TAG, "Mocking is active, bypassing async GPS fetch and directly returning fallback/mock coords.")
            // 若正在模擬，直接回傳最後傳入的 mock 座標（UI 應該傳入 currentMockLat 等）
            if (!isInvalidLocation(fallbackLat, fallbackLng)) {
                onSuccess(fallbackLat, fallbackLng)
            } else {
                onFailure()
            }
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val cancellationTokenSource = CancellationTokenSource()

        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null && !isInvalidLocation(location.latitude, location.longitude)) {
                    onSuccess(location.latitude, location.longitude)
                } else {
                    // 退回取得最後已知位置
                    fallbackToLastKnownAsync(fusedLocationClient, context, fallbackLat, fallbackLng, onSuccess, onFailure)
                }
            }.addOnFailureListener {
                Log.w(TAG, "getCurrentLocation failed", it)
                fallbackToLastKnownAsync(fusedLocationClient, context, fallbackLat, fallbackLng, onSuccess, onFailure)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing location permission during async fetch", e)
            fallbackToLastKnownAsync(fusedLocationClient, context, fallbackLat, fallbackLng, onSuccess, onFailure)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackToLastKnownAsync(
        fusedLocationClient: FusedLocationProviderClient,
        context: Context,
        fallbackLat: Double,
        fallbackLng: Double,
        onSuccess: (Double, Double) -> Unit,
        onFailure: () -> Unit
    ) {
        try {
            // 先嘗試 FusedLocation 的 lastLocation
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null && !isInvalidLocation(location.latitude, location.longitude)) {
                    onSuccess(location.latitude, location.longitude)
                } else {
                    executeSyncFallback(context, fallbackLat, fallbackLng, onSuccess, onFailure)
                }
            }.addOnFailureListener {
                executeSyncFallback(context, fallbackLat, fallbackLng, onSuccess, onFailure)
            }
        } catch (e: SecurityException) {
            executeSyncFallback(context, fallbackLat, fallbackLng, onSuccess, onFailure)
        }
    }

    private fun executeSyncFallback(
        context: Context,
        fallbackLat: Double,
        fallbackLng: Double,
        onSuccess: (Double, Double) -> Unit,
        onFailure: () -> Unit
    ) {
        val (lat, lng) = getBestAvailableLocationSync(context, fallbackLat, fallbackLng)
        if (!isInvalidLocation(lat, lng)) {
            Log.d(TAG, "Async fetch fell back to synchronous cache success.")
            onSuccess(lat, lng)
        } else {
            Log.w(TAG, "All location retrieval methods failed.")
            onFailure()
        }
    }

    private fun isInvalidLocation(lat: Double, lng: Double): Boolean {
        return kotlin.math.abs(lat) < 0.0001 && kotlin.math.abs(lng) < 0.0001
    }
}
