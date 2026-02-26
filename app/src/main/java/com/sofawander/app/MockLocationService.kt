package com.sofawander.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sofawander.app.data.AppDatabase
import com.sofawander.app.data.GpsEventEntity
import com.sofawander.app.data.RouteJson
import com.sofawander.app.data.RoutePoint
import com.sofawander.app.data.RunHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.gson.Gson
import com.sofawander.app.R


class MockLocationService : Service() {

    private val TAG = "MockLocSvc"

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var locationManager: LocationManager? = null
    private val activeProviders = mutableSetOf<String>()
    private var hasReportedError = false
    private val routePoints = mutableListOf<RoutePoint>()
    private var currentSegmentIndex = 0
    private var distanceOnSegment = 0.0
    private var speedMetersPerSecond = SPEED_WALK
    private var speedMinMetersPerSecond = 0.0
    private var speedMaxMetersPerSecond = 0.0
    private var pauseMinSeconds = 0.0
    private var pauseMaxSeconds = 0.0
    private var currentSegmentSpeed = SPEED_WALK
    private var lastUpdateRealtime = 0L
    private var isPaused = false
    private var pauseUntilRealtime = 0L
    private var isRandomSpeed = false
    private var isLoopEnabled = false
    private var isRoundTripEnabled = false
    private var loopCountTarget = 0
    private var currentLoopCount = 0
    private var isForward = true
    private var driftEnabled = false
    private var bounceEnabled = false
    private var smoothingEnabled = false
    private var driftMeters = 0.0
    private var bounceMeters = 0.0
    private var smoothingAlpha = 0.0
    private var smoothedLat: Double? = null
    private var smoothedLng: Double? = null
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0

    private fun isInvalidLocation(lat: Double, lng: Double): Boolean {
        // Checking for exactly 0,0 or very near 0,0 (Null Island)
        return (kotlin.math.abs(lat) < 0.0001 && kotlin.math.abs(lng) < 0.0001)
    }

    private fun loadLastLocation() {
        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            lastLat = prefs.getFloat("pref_last_mock_lat", 0f).toDouble()
            lastLng = prefs.getFloat("pref_last_mock_lng", 0f).toDouble()
            Log.i(TAG, "Loaded last location from prefs: $lastLat, $lastLng")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading last location: ${e.message}")
            lastLat = 0.0
            lastLng = 0.0
        }
    }

    private fun saveLastLocation(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) return
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putFloat("pref_last_mock_lat", lat.toFloat())
            .putFloat("pref_last_mock_lng", lng.toFloat())
            .apply()
    }
    private var bouncePhase = 0.0
    
    // Joystick state
    private var isJoystickActive = false
    private var joystickDx = 0f
    private var joystickDy = 0f
    private var joystickRunnable: Runnable? = null
    @Volatile private var activeRunId: Long? = null
    private val db by lazy { AppDatabase.getInstance(this) }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Progress tracking
    private var totalRouteDistance = 0.0
    private var distanceTraveled = 0.0
    private var startTimeMs = 0L
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "mock_location"

    private var providerReady = false

    override fun onCreate() {
        try {
            super.onCreate()
            Log.i(TAG, "onCreate: Starting service...")
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            loadLastLocation()

            // Persistent background thread for all mock movements
            handlerThread = HandlerThread("MockLocationBackgroundThread").apply { start() }
            handler = Handler(handlerThread!!.looper)
            
            startInForeground()
            Log.i(TAG, "onCreate: Service created successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Failed to create service", e)
            throw e // Re-throw to make it visible to user, but now we have Log records
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_ROUTE -> {
                val json = intent.getStringExtra(EXTRA_ROUTE_JSON).orEmpty()
                val speedMode = intent.getIntExtra(EXTRA_SPEED_MODE, 0)
                val speedMinKmh = intent.getDoubleExtra(EXTRA_SPEED_MIN_KMH, 0.0)
                val speedMaxKmh = intent.getDoubleExtra(EXTRA_SPEED_MAX_KMH, 0.0)
                val pauseMin = intent.getDoubleExtra(EXTRA_PAUSE_MIN_SEC, 0.0)
                val pauseMax = intent.getDoubleExtra(EXTRA_PAUSE_MAX_SEC, 0.0)
                isRandomSpeed = intent.getBooleanExtra(EXTRA_RANDOM_SPEED, false)
                isLoopEnabled = intent.getBooleanExtra(EXTRA_LOOP_ENABLED, false)
                isRoundTripEnabled = intent.getBooleanExtra(EXTRA_ROUNDTRIP_ENABLED, false)
                loopCountTarget = intent.getIntExtra(EXTRA_LOOP_COUNT, 0)
                currentLoopCount = 0
                driftEnabled = intent.getBooleanExtra(EXTRA_DRIFT_ENABLED, false)
                bounceEnabled = intent.getBooleanExtra(EXTRA_BOUNCE_ENABLED, false)
                smoothingEnabled = intent.getBooleanExtra(EXTRA_SMOOTHING_ENABLED, false)
                driftMeters = intent.getDoubleExtra(EXTRA_DRIFT_METERS, 0.0)
                bounceMeters = intent.getDoubleExtra(EXTRA_BOUNCE_METERS, 0.0)
                smoothingAlpha = intent.getDoubleExtra(EXTRA_SMOOTHING_ALPHA, 0.0)
                val walkToStart = intent.getBooleanExtra(EXTRA_WALK_TO_START, false)
                val points = RouteJson.fromJson(json)
                if (points.size >= 2) {
                    routePoints.clear()
                    routePoints.addAll(points)
                    
                    if (walkToStart) {
                        var startLat = intent.getDoubleExtra("extra_start_lat", 0.0)
                        var startLng = intent.getDoubleExtra("extra_start_lng", 0.0)
                        if (startLat == 0.0 || startLng == 0.0) {
                            startLat = lastLat
                            startLng = lastLng
                        }
                        if (isInvalidLocation(startLat, startLng)) {
                            val (bestLat, bestLng) = LocationHelper.getBestAvailableLocationSync(this@MockLocationService, lastLat, lastLng)
                            startLat = if (isInvalidLocation(bestLat, bestLng)) routePoints[0].latitude else bestLat
                            startLng = if (isInvalidLocation(bestLat, bestLng)) routePoints[0].longitude else bestLng
                        }
                        routePoints.add(0, RoutePoint(startLat, startLng))
                        lastLat = startLat
                        lastLng = startLng
                    } else {
                        // Teleport directly to start point to avoid skipping the first point
                        lastLat = routePoints[0].latitude
                        lastLng = routePoints[0].longitude
                    }

                    currentSegmentIndex = 0
                    distanceOnSegment = 0.0
                    isForward = true
                    pauseUntilRealtime = 0L
                    smoothedLat = null
                    smoothedLng = null
                    bouncePhase = 0.0
                    pauseMinSeconds = pauseMin.coerceAtLeast(0.0)
                    pauseMaxSeconds = pauseMax.coerceAtLeast(0.0)
                    if (pauseMaxSeconds < pauseMinSeconds) {
                        pauseMaxSeconds = pauseMinSeconds
                    }
                    speedMetersPerSecond = when (speedMode) {
                        SPEED_MODE_JOG -> SPEED_JOG
                        SPEED_MODE_DRIVE -> SPEED_DRIVE
                        else -> SPEED_WALK
                    }
                    speedMinMetersPerSecond = kmhToMps(speedMinKmh)
                    speedMaxMetersPerSecond = kmhToMps(speedMaxKmh)
                    if (speedMaxMetersPerSecond < speedMinMetersPerSecond) {
                        speedMaxMetersPerSecond = speedMinMetersPerSecond
                    }
                    currentSegmentSpeed = pickSegmentSpeed()
                    isPaused = false
                    lastUpdateRealtime = SystemClock.elapsedRealtime()

                    // 計算路線總距離
                    totalRouteDistance = 0.0
                    for (i in 0 until routePoints.size - 1) {
                        totalRouteDistance += haversineMeters(
                            routePoints[i].latitude, routePoints[i].longitude,
                            routePoints[i + 1].latitude, routePoints[i + 1].longitude
                        )
                    }
                    distanceTraveled = 0.0
                    startTimeMs = System.currentTimeMillis()

                    // 初始化 test provider 並啟動路線播放循環
                    if (!providerReady) {
                        ensureTestProvider()
                    }
                    Log.d(TAG, "onStartCommand: providerReady=$providerReady, points=${routePoints.size}")
                    stopActiveMovement()
                    startRouteLoop()

                    setRunningFlag(true)
                    broadcastStatus(getString(R.string.status_running), "")
                    updateNotification()
                    val routeJson = intent.getStringExtra(EXTRA_ROUTE_JSON) ?: ""
                    insertRunHistory(points.size, speedMode, routeJson)
                }
            }
            ACTION_PAUSE_ROUTE -> {
                isPaused = !isPaused
                broadcastStatus(
                    if (isPaused) getString(R.string.status_paused) else getString(R.string.status_running),
                    ""
                )
                updateNotification()
            }
            ACTION_STOP_ROUTE -> {
                Log.i(TAG, "Stopping all movement and service")
                stopActiveMovement()
                setRunningFlag(false)
                broadcastStatus(getString(R.string.status_idle), "")
                startIdleLoop()
            }
            ACTION_UPDATE_SPEED -> {
                val speedMinKmh = intent.getDoubleExtra(EXTRA_SPEED_MIN_KMH, 0.0)
                val speedMaxKmh = intent.getDoubleExtra(EXTRA_SPEED_MAX_KMH, 0.0)
                
                speedMinMetersPerSecond = kmhToMps(speedMinKmh)
                speedMaxMetersPerSecond = kmhToMps(speedMaxKmh)
                if (speedMaxMetersPerSecond < speedMinMetersPerSecond) {
                    speedMaxMetersPerSecond = speedMinMetersPerSecond
                }

                // Update base speed as well
                speedMetersPerSecond = (speedMinMetersPerSecond + speedMaxMetersPerSecond) / 2.0
                
                // Immediately apply new speed to the current segment
                currentSegmentSpeed = pickSegmentSpeed()
                Log.i(TAG, "Speed updated: base=${speedMetersPerSecond * 3.6} km/h")
            }
            ACTION_TELEPORT -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                val walkMode = intent.getBooleanExtra(EXTRA_WALK_MODE, false)

                if (lat != 0.0 || lng != 0.0) {
                    if (!providerReady) {
                        ensureTestProvider()
                    }

                    if (walkMode) {
                        // Walk Mode: 從目前位置走到目標
                        var startLat = intent.getDoubleExtra("extra_start_lat", 0.0)
                        var startLng = intent.getDoubleExtra("extra_start_lng", 0.0)

                        if (isInvalidLocation(startLat, startLng)) {
                            val (bestLat, bestLng) = LocationHelper.getBestAvailableLocationSync(this@MockLocationService, lastLat, lastLng)
                            startLat = if (isInvalidLocation(bestLat, bestLng)) lat else bestLat
                            startLng = if (isInvalidLocation(bestLat, bestLng)) lng else bestLng
                        }

                        val points = listOf(RoutePoint(startLat, startLng), RoutePoint(lat, lng))
                        routePoints.clear()
                        routePoints.addAll(points)
                        lastLat = startLat
                        lastLng = startLng
                        currentSegmentIndex = 0
                        distanceOnSegment = 0.0
                        isForward = true
                        pauseUntilRealtime = 0L
                        // Use current speed settings instead of resetting to SPEED_WALK (9 km/h)
                        currentSegmentSpeed = pickSegmentSpeed()
                        isPaused = false
                        lastUpdateRealtime = SystemClock.elapsedRealtime()

                        totalRouteDistance = haversineMeters(startLat, startLng, lat, lng)
                        distanceTraveled = 0.0
                        startTimeMs = System.currentTimeMillis()

                        stopActiveMovement()
                        startRouteLoop()
                        setRunningFlag(true)
                        broadcastStatus(getString(R.string.status_running), "Walking to $lat, $lng")
                        broadcastRoute(points)
                        updateNotification()
                    } else {
                        // Instant Jump
                        stopActiveMovement()
                        distanceTraveled = 0.0
                        totalRouteDistance = 0.0
                        startTimeMs = System.currentTimeMillis()
                        pushMockLocation(lat, lng)
                        broadcastStatus(
                            getString(R.string.status_teleported),
                            "Jumped to $lat, $lng"
                        )
                        broadcastProgress()
                        broadcastRoute(emptyList())
                    }
                }
            }
            ACTION_JOYSTICK_MOVE -> {
                joystickDx = intent.getFloatExtra(EXTRA_JOYSTICK_DX, 0f)
                joystickDy = intent.getFloatExtra(EXTRA_JOYSTICK_DY, 0f)
                val wasActive = isJoystickActive
                isJoystickActive = (joystickDx != 0f || joystickDy != 0f)
                
                if (isJoystickActive && !wasActive) {
                    // 如果正在執行路線，自動進入暫停模式
                    if (routePoints.isNotEmpty() && !isPaused) {
                        isPaused = true
                        broadcastStatus(getString(R.string.status_paused), "Joystick auto-paused route")
                        updateNotification()
                    }
                    
                    val startLat = intent.getDoubleExtra("extra_start_lat", 0.0)
                    val startLng = intent.getDoubleExtra("extra_start_lng", 0.0)
                    
                    if (!isInvalidLocation(startLat, startLng)) {
                        lastLat = startLat
                        lastLng = startLng
                    } else if (isInvalidLocation(lastLat, lastLng)) {
                        val (bestLat, bestLng) = LocationHelper.getBestAvailableLocationSync(this@MockLocationService, 0.0, 0.0)
                        if (!isInvalidLocation(bestLat, bestLng)) {
                            lastLat = bestLat
                            lastLng = bestLng
                        }
                    }
                    if (isInvalidLocation(lastLat, lastLng)) {
                        // Prevent sending 0,0 to the system. Skip joystick.
                        isJoystickActive = false
                        return START_STICKY
                    }
                    if (!providerReady) {
                        ensureTestProvider()
                    }
                    if (routePoints.isEmpty()) {
                        stopActiveMovement()
                        setRunningFlag(true)
                    }
                    isJoystickActive = true
                    startJoystickLoop()
                    broadcastStatus(getString(R.string.status_running), "Joystick Active")
                } else if (!isJoystickActive && wasActive) {
                    stopJoystickLoop()
                    if (routePoints.isEmpty()) {
                        stopActiveMovement()
                        setRunningFlag(false)
                        broadcastStatus(getString(R.string.status_idle), "")
                    } else if (isPaused) {
                        broadcastStatus(getString(R.string.status_paused), "Joystick stopped")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopActiveMovement()
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        removeTestProvider()
        finishRunHistory(RUN_STATUS_STOPPED)
        setRunningFlag(false)
        broadcastStatus(getString(R.string.status_idle), "")
        ioScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isRunning = prefs.getBoolean(PREF_KEY_RUNNING, false)
        if (!isRunning && !isJoystickActive) {
            Log.i(TAG, "onTaskRemoved: Stopping service to release GPS provider")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mock Location",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = buildNotification("Waiting for route...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pauseIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_PAUSE_ROUTE
        }
        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP_ROUTE
        }

        val pausePending = android.app.PendingIntent.getService(
            this, 0, pauseIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = android.app.PendingIntent.getService(
            this, 1, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (routePoints.size >= 2) {
            val pauseLabel = if (isPaused) getString(R.string.action_resume) else getString(R.string.action_pause)
            builder.addAction(0, pauseLabel, pausePending)
            builder.addAction(0, getString(R.string.action_stop), stopPending)
        }

        return builder.build()
    }

    private fun updateNotification() {
        val elapsed = System.currentTimeMillis() - startTimeMs
        val distKm = distanceTraveled / 1000.0
        val totalKm = totalRouteDistance / 1000.0
        val speedKmh = currentSegmentSpeed * 3.6
        val elapsedStr = formatDuration(elapsed)
        
        var etaMs = 0L
        if (distanceTraveled > 0 && elapsed > 0 && totalRouteDistance > distanceTraveled) {
            val pace = distanceTraveled / elapsed
            if (pace > 0.0000001) {
                etaMs = ((totalRouteDistance - distanceTraveled) / pace).toLong()
                if (etaMs > 86400000L * 7) etaMs = 86400000L * 7
            }
        }
        
        val etaStr = if (etaMs > 0) formatDuration(elapsed + etaMs) else "--:--"

        val text = "%.2f/%.2f km | %s/%s | %.1f km/h".format(
            distKm, totalKm, elapsedStr, etaStr, speedKmh
        )

        val notification = buildNotification(text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min >= 60) {
            val hr = min / 60
            "%d:%02d:%02d".format(hr, min % 60, sec)
        } else {
            "%02d:%02d".format(min, sec)
        }
    }

    private fun startRouteLoop() {
        handler?.post(object : Runnable {
            override fun run() {
                // Check if we were stopped
                if (handlerThread == null) return
                if (!isJoystickActive) {
                    updatePlayback()
                }
                handler?.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        })
    }

    private var idleRunnable: Runnable? = null
    private var isIdleLoopRunning = false
    private fun startIdleLoop() {
        if (isIdleLoopRunning) return
        isIdleLoopRunning = true
        idleRunnable = object : Runnable {
            override fun run() {
                if (handlerThread == null || !isIdleLoopRunning) return
                if (!isJoystickActive && routePoints.isEmpty()) {
                    if (!isInvalidLocation(lastLat, lastLng)) {
                        if (!providerReady) {
                            ensureTestProvider()
                        }
                        pushMockLocation(lastLat, lastLng)
                    }
                }
                handler?.postDelayed(this, 1000L) // Push every 1 second to prevent MapLibre stale (gray) state
            }
        }
        handler?.post(idleRunnable!!)
    }

    private fun stopActiveMovement() {
        isJoystickActive = false
        isIdleLoopRunning = false
        handler?.removeCallbacksAndMessages(null)
        // Note: we don't quit handlerThread here as it's persistent
    }

    private fun stopRouteLoop() {
        // Redundant with stopActiveMovement, kept for compatibility if needed
        stopActiveMovement()
    }

    @Suppress("DEPRECATION")
    private fun ensureTestProvider() {
        val lm = locationManager ?: return
        
        if (providerReady) {
            Log.i(TAG, "ensureTestProvider: Provider already ready.")
            return
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused" // Fused provider often used by modern apps
        )
        
        activeProviders.clear()
        for (provider in providers) {
            try {
                // Try to remove first for a clean state
                try {
                    lm.removeTestProvider(provider)
                } catch (_: Exception) {}

                Log.i(TAG, "ensureTestProvider: Initializing $provider")
                lm.addTestProvider(
                    provider,
                    provider != LocationManager.GPS_PROVIDER, // requiresNetwork
                    provider == LocationManager.GPS_PROVIDER, // requiresSatellite
                    false, false, true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                
                lm.setTestProviderEnabled(provider, true)
                lm.setTestProviderStatus(provider, android.location.LocationProvider.AVAILABLE, null, System.currentTimeMillis())
                activeProviders.add(provider)
                Log.i(TAG, "ensureTestProvider: Enabled and set status to AVAILABLE for $provider")
            } catch (e: SecurityException) {
                Log.e(TAG, "addTestProvider SecurityException for $provider: ${e.message}")
                reportMockAppError()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Error initializing provider $provider: ${e.message}")
            }
        }
        providerReady = activeProviders.isNotEmpty()
    }

    @Suppress("DEPRECATION")
    private fun removeTestProvider() {
        val lm = locationManager ?: return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
        for (p in providers) {
            try {
                lm.removeTestProvider(p)
            } catch (_: Exception) { }
        }
        activeProviders.clear()
        providerReady = false
    }

    private fun pushMockLocation(latitude: Double, longitude: Double) {
        if (isInvalidLocation(latitude, longitude)) {
            Log.w(TAG, "pushMockLocation: Ignoring Null Island jump ($latitude, $longitude)")
            return
        }
        lastLat = latitude
        lastLng = longitude
        saveLastLocation(latitude, longitude)

        if (!providerReady) {
            Log.w(TAG, "pushMockLocation: provider not ready, ignoring $latitude, $longitude")
            return
        }
        val lm = locationManager ?: return
        
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        
        // Use activeProviders set rather than a fixed list to avoid IAExceptions
        if (activeProviders.isEmpty()) return
        
        for (p in activeProviders) {
            val location = Location(p).apply {
                this.latitude = latitude
                this.longitude = longitude
                accuracy = 1.0f // More accurate
                altitude = 0.0
                speed = currentSegmentSpeed.toFloat()
                bearing = 0f
                time = now
                elapsedRealtimeNanos = elapsed
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = 1f
                    speedAccuracyMetersPerSecond = 0.1f
                    bearingAccuracyDegrees = 1f
                }
            }

            try {
                lm.setTestProviderLocation(p, location)
                if (p == LocationManager.GPS_PROVIDER) logGpsEvent(location)
                // Log.i(TAG, "pushMockLocation: Successfully pushed to $p ($latitude, $longitude)")
            } catch (e: SecurityException) {
                Log.e(TAG, "pushMockLocation SecurityException for $p: ${e.message}")
                reportMockAppError()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "pushMockLocation IAE for $p: ${e.message}")
                providerReady = false
            } catch (e: Exception) {
                Log.e(TAG, "pushMockLocation unexpected error for $p: ${e.message}")
            }
        }
    }

    private fun applyRealism(lat: Double, lng: Double): Pair<Double, Double> {
        var latOut = lat
        var lngOut = lng

        if (bounceEnabled && bounceMeters > 0.0) {
            bouncePhase += 0.35
            val offsetMeters = Math.sin(bouncePhase) * bounceMeters
            val offset = metersToLatLng(latOut, offsetMeters, 90.0)
            latOut += offset.first
            lngOut += offset.second
        }

        if (driftEnabled && driftMeters > 0.0) {
            val angle = Math.random() * 360.0
            val offset = metersToLatLng(latOut, driftMeters, angle)
            latOut += offset.first
            lngOut += offset.second
        }

        if (smoothingEnabled) {
            val alpha = smoothingAlpha.coerceIn(0.0, 1.0)
            val prevLat = smoothedLat ?: latOut
            val prevLng = smoothedLng ?: lngOut
            val smoothLat = prevLat + (latOut - prevLat) * alpha
            val smoothLng = prevLng + (lngOut - prevLng) * alpha
            smoothedLat = smoothLat
            smoothedLng = smoothLng
            return smoothLat to smoothLng
        }

        return latOut to lngOut
    }

    private fun metersToLatLng(baseLat: Double, meters: Double, bearingDegrees: Double): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val delta = meters / earthRadius
        val bearing = Math.toRadians(bearingDegrees)
        val dLat = delta * Math.cos(bearing)
        val dLng = delta * Math.sin(bearing) / Math.cos(Math.toRadians(baseLat))
        return Math.toDegrees(dLat) to Math.toDegrees(dLng)
    }

    private fun updatePlayback() {
        if (routePoints.size < 2) return
        if (isPaused) {
            lastUpdateRealtime = SystemClock.elapsedRealtime()
            if (!isInvalidLocation(lastLat, lastLng)) {
                pushMockLocation(lastLat, lastLng)
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (pauseUntilRealtime > now) {
            lastUpdateRealtime = now
            if (!isInvalidLocation(lastLat, lastLng)) {
                pushMockLocation(lastLat, lastLng)
            }
            return
        }

        val dtSeconds = (now - lastUpdateRealtime) / 1000.0
        lastUpdateRealtime = now
        if (dtSeconds <= 0) return

        // 每個循環重新獲取速度，確保速度同步
        currentSegmentSpeed = pickSegmentSpeed()
        var remainingMove = currentSegmentSpeed * dtSeconds

        while (remainingMove > 0) {
            val nextIndex = nextSegmentIndex() ?: break
            val target = routePoints[nextIndex]
            
            // Dynamic tracking: Move from CURRENT location towards the target point
            val distanceToTarget = haversineMeters(lastLat, lastLng, target.latitude, target.longitude)

            if (distanceToTarget <= 0.1) { // Very close to target
                currentSegmentIndex = nextIndex
                distanceOnSegment = 0.0 // Reset segment progress
                
                // 抵達航點時檢索速度與暫停設定
                maybePause()
                currentSegmentSpeed = pickSegmentSpeed()
                
                if (isPaused || pauseUntilRealtime > SystemClock.elapsedRealtime()) break
                continue
            }

            if (remainingMove < distanceToTarget) {
                // Move partially towards target
                // 使用 atan2 修正方位角
                val bearing = Math.toDegrees(Math.atan2(
                    target.longitude - lastLng,
                    target.latitude - lastLat
                ))
                
                val newLoc = movePoint(lastLat, lastLng, remainingMove, bearing)
                
                distanceTraveled += remainingMove
                distanceOnSegment += remainingMove 
                
                val adjusted = applyRealism(newLoc[0], newLoc[1])
                pushMockLocation(adjusted.first, adjusted.second)
                broadcastProgress()
                updateNotification()
                return
            }

            // Reached the target point in this step
            distanceTraveled += distanceToTarget
            remainingMove -= distanceToTarget
            currentSegmentIndex = nextIndex
            distanceOnSegment = 0.0
            
            // Teleport exactly to target to avoid drift accumulation
            pushMockLocation(target.latitude, target.longitude)
            
            maybePause()
            currentSegmentSpeed = pickSegmentSpeed()
            
            if (isPaused || pauseUntilRealtime > SystemClock.elapsedRealtime()) break
        }

        // Only handle completion if we actually ran out of segments
        if (nextSegmentIndex() == null) {
            handleRouteCompletion()
        }
    }

    private fun nextSegmentIndex(): Int? {
        return if (isForward) {
            if (currentSegmentIndex >= routePoints.lastIndex) null else currentSegmentIndex + 1
        } else {
            if (currentSegmentIndex <= 0) null else currentSegmentIndex - 1
        }
    }

    private fun handleRouteCompletion() {
        val lastIndex = if (isForward) routePoints.lastIndex else 0
        val last = routePoints[lastIndex]
        val adjusted = applyRealism(last.latitude, last.longitude)
        pushMockLocation(adjusted.first, adjusted.second)

        // Increment loop metrics
        if (isRoundTripEnabled) {
            // A full round trip is counted only when returning to start (i.e. finishing backward pass)
            if (!isForward) {
                currentLoopCount++
            }
        } else {
            // Forward only, so every completion is one loop
            currentLoopCount++
        }

        if (loopCountTarget > 0 && currentLoopCount >= loopCountTarget) {
            // Reached target loops, fall through to stop
        } else if (isRoundTripEnabled) {
            isForward = !isForward
            distanceOnSegment = 0.0
            currentSegmentSpeed = pickSegmentSpeed()
            return
        } else if (isLoopEnabled) {
            currentSegmentIndex = 0
            distanceOnSegment = 0.0
            currentSegmentSpeed = pickSegmentSpeed()
            return
        }

        stopActiveMovement()
        broadcastStatus(getString(R.string.status_completed), "")
        finishRunHistory(RUN_STATUS_COMPLETED)
        setRunningFlag(false)
        startIdleLoop()
    }

    private fun maybePause() {
        if (pauseMaxSeconds <= 0.0) return
        val duration = randomBetween(pauseMinSeconds, pauseMaxSeconds)
        if (duration <= 0.0) return
        pauseUntilRealtime = SystemClock.elapsedRealtime() + (duration * 1000).toLong()
    }

    private fun pickSegmentSpeed(): Double {
        if (!isRandomSpeed) return speedMetersPerSecond
        if (speedMaxMetersPerSecond <= 0.0) return speedMetersPerSecond
        val min = if (speedMinMetersPerSecond > 0.0) speedMinMetersPerSecond else speedMetersPerSecond
        val max = if (speedMaxMetersPerSecond > 0.0) speedMaxMetersPerSecond else speedMetersPerSecond
        return randomBetween(min, max)
    }

    private fun startJoystickLoop() {
        lastUpdateRealtime = SystemClock.elapsedRealtime()
        joystickRunnable = object : Runnable {
            override fun run() {
                if (!isJoystickActive) return

                val now = SystemClock.elapsedRealtime()
                val elapsedMs = (now - lastUpdateRealtime).coerceAtLeast(1)
                lastUpdateRealtime = now

                // Use the currently picked speed (which reacts to ACTION_UPDATE_SPEED)
                val baseSpeed = pickSegmentSpeed()
                
                val length = kotlin.math.sqrt((joystickDx * joystickDx + joystickDy * joystickDy).toDouble())
                if (length > 0) {
                    // Normalize length to 1.0 max (it should already be normalized from Activity)
                    val scale = length.coerceAtMost(1.0)
                    val distanceToMoveMeters = baseSpeed * scale * (elapsedMs / 1000.0)

                    val ndx = joystickDx / length
                    val ndy = joystickDy / length
                    
                    // Note: y axis on screen is inverted (down is positive), so -ndy is North
                    val bearing = Math.toDegrees(Math.atan2(ndx.toDouble(), -ndy.toDouble()))
                    
                    val newLoc = movePoint(lastLat, lastLng, distanceToMoveMeters, bearing)
                    lastLat = newLoc[0]
                    lastLng = newLoc[1]
                    
                    distanceTraveled += distanceToMoveMeters
                    pushMockLocation(lastLat, lastLng)
                    broadcastProgress()
                }

                if (isJoystickActive) {
                    handler?.postDelayed(this, 100) // 10 updates per second
                }
            }
        }
        handler?.post(joystickRunnable!!)
    }
    
    private fun stopJoystickLoop() {
        isJoystickActive = false
        joystickRunnable?.let { handler?.removeCallbacks(it) }
        
        if (routePoints.size >= 2 && isPaused && activeRunId != null) {
            broadcastStatus(getString(R.string.status_paused), "")
        }
    }
    
    private fun movePoint(lat: Double, lng: Double, distanceMeters: Double, bearingDegrees: Double): DoubleArray {
        val r = 6371000.0 // Earth radius
        val brng = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lng)
        
        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(distanceMeters/r) +
                             Math.cos(lat1) * Math.sin(distanceMeters/r) * Math.cos(brng))
        val lon2 = lon1 + Math.atan2(Math.sin(brng) * Math.sin(distanceMeters/r) * Math.cos(lat1),
                                     Math.cos(distanceMeters/r) - Math.sin(lat1) * Math.sin(lat2))
                                     
        return doubleArrayOf(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun randomBetween(min: Double, max: Double): Double {
        if (max <= min) return min
        return min + Math.random() * (max - min)
    }

    private fun kmhToMps(kmh: Double): Double {
        return kmh * 1000.0 / 3600.0
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun reportMockAppError() {
        if (hasReportedError) return
        hasReportedError = true
        broadcastStatus(
            getString(R.string.status_error),
            getString(R.string.error_not_mock_app)
        )
    }

    private fun insertRunHistory(pointCount: Int, speedMode: Int, routePointsJson: String) {
        val now = System.currentTimeMillis()
        val entity = RunHistoryEntity(
            routeName = null,
            pointCount = pointCount,
            speedMode = speedMode,
            loopEnabled = isLoopEnabled,
            roundTripEnabled = isRoundTripEnabled,
            startedAt = now,
            status = RUN_STATUS_RUNNING,
            routePointsJson = routePointsJson
        )
        ioScope.launch {
            activeRunId = db.runHistoryDao().insert(entity)
        }
    }

    private fun finishRunHistory(status: String) {
        val id = activeRunId ?: return
        activeRunId = null
        val end = System.currentTimeMillis()
        ioScope.launch {
            db.runHistoryDao().updateEnd(id, end, status)
        }
    }

    private fun logGpsEvent(location: Location) {
        val rid = activeRunId ?: return
        val event = GpsEventEntity(
            runId = rid,
            timestamp = System.currentTimeMillis(),
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            speedMps = currentSegmentSpeed
        )
        ioScope.launch {
            db.gpsEventDao().insert(event)
        }
    }

    private fun broadcastStatus(status: String, message: String) {
        val intent = Intent(ACTION_MOCK_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun broadcastProgress() {
        val elapsed = System.currentTimeMillis() - startTimeMs
        val intent = Intent(ACTION_MOCK_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_DISTANCE_TRAVELED, distanceTraveled)
            putExtra(EXTRA_TOTAL_DISTANCE, totalRouteDistance)
            putExtra(EXTRA_ELAPSED_MS, elapsed)
            putExtra(EXTRA_CURRENT_SPEED_KMH, currentSegmentSpeed * 3.6)
            putExtra(EXTRA_LAT, lastLat)
            putExtra(EXTRA_LNG, lastLng)
        }
        sendBroadcast(intent)
    }

    private fun broadcastRoute(points: List<RoutePoint>) {
        val json = RouteJson.toJson(points)
        val intent = Intent(ACTION_ROUTE_UPDATED).apply {
            setPackage(packageName)
            putExtra(EXTRA_ROUTE_JSON, json)
        }
        sendBroadcast(intent)
    }
    private fun setRunningFlag(isRunning: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_RUNNING, isRunning).apply()
    }

    companion object {
        const val ACTION_MOCK_STATUS = "com.sofawander.app.MOCK_STATUS"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_MESSAGE = "extra_message"
        const val ACTION_START_ROUTE = "com.sofawander.app.ACTION_START_ROUTE"
        const val ACTION_PAUSE_ROUTE = "com.sofawander.app.ACTION_PAUSE_ROUTE"
        const val ACTION_STOP_ROUTE = "com.sofawander.app.ACTION_STOP_ROUTE"
        const val ACTION_UPDATE_SPEED = "com.sofawander.app.ACTION_UPDATE_SPEED"
        const val ACTION_TELEPORT = "com.sofawander.app.ACTION_TELEPORT"
        const val ACTION_ROUTE_UPDATED = "com.sofawander.app.ACTION_ROUTE_UPDATED"

        const val ACTION_JOYSTICK_MOVE = "com.sofawander.app.ACTION_JOYSTICK_MOVE"
        const val EXTRA_JOYSTICK_DX = "extra_joystick_dx"
        const val EXTRA_JOYSTICK_DY = "extra_joystick_dy"

        const val EXTRA_WALK_MODE = "extra_walk_mode"
        const val EXTRA_WALK_TO_START = "extra_walk_to_start"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_ROUTE_JSON = "extra_route_json"
        const val EXTRA_SPEED_MODE = "extra_speed_mode"
        const val EXTRA_SPEED_MIN_KMH = "extra_speed_min_kmh"
        const val EXTRA_SPEED_MAX_KMH = "extra_speed_max_kmh"
        const val EXTRA_PAUSE_MIN_SEC = "extra_pause_min_sec"
        const val EXTRA_PAUSE_MAX_SEC = "extra_pause_max_sec"
        const val EXTRA_RANDOM_SPEED = "extra_random_speed"
        const val EXTRA_LOOP_ENABLED = "extra_loop_enabled"
        const val EXTRA_LOOP_COUNT = "extra_loop_count"
        const val EXTRA_ROUNDTRIP_ENABLED = "extra_roundtrip_enabled"
        const val EXTRA_DRIFT_ENABLED = "extra_drift_enabled"
        const val EXTRA_BOUNCE_ENABLED = "extra_bounce_enabled"
        const val EXTRA_SMOOTHING_ENABLED = "extra_smoothing_enabled"
        const val EXTRA_DRIFT_METERS = "extra_drift_meters"
        const val EXTRA_BOUNCE_METERS = "extra_bounce_meters"
        const val EXTRA_SMOOTHING_ALPHA = "extra_smoothing_alpha"
        
        const val RUN_STATUS_RUNNING = "RUNNING"
        const val RUN_STATUS_COMPLETED = "COMPLETED"
        const val RUN_STATUS_STOPPED = "STOPPED"
        const val PREFS_NAME = "mock_prefs"
        const val PREF_KEY_RUNNING = "mock_service_running"
        const val UPDATE_INTERVAL_MS = 1000L
        const val SPEED_MODE_JOG = 1
        const val SPEED_MODE_DRIVE = 2
        const val SPEED_WALK = 2.5
        const val SPEED_JOG = 4.0
        const val SPEED_DRIVE = 13.9

        const val ACTION_MOCK_PROGRESS = "com.sofawander.app.MOCK_PROGRESS"
        const val EXTRA_DISTANCE_TRAVELED = "extra_distance_traveled"
        const val EXTRA_TOTAL_DISTANCE = "extra_total_distance"
        const val EXTRA_ELAPSED_MS = "extra_elapsed_ms"
        const val EXTRA_CURRENT_SPEED_KMH = "extra_current_speed_kmh"
    }
}
