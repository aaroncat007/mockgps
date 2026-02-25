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
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Persistent background thread for all mock movements
        handlerThread = HandlerThread("MockLocationBackgroundThread").apply { start() }
        handler = Handler(handlerThread!!.looper)
        
        startInForeground()
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
                driftEnabled = intent.getBooleanExtra(EXTRA_DRIFT_ENABLED, false)
                bounceEnabled = intent.getBooleanExtra(EXTRA_BOUNCE_ENABLED, false)
                smoothingEnabled = intent.getBooleanExtra(EXTRA_SMOOTHING_ENABLED, false)
                driftMeters = intent.getDoubleExtra(EXTRA_DRIFT_METERS, 0.0)
                bounceMeters = intent.getDoubleExtra(EXTRA_BOUNCE_METERS, 0.0)
                smoothingAlpha = intent.getDoubleExtra(EXTRA_SMOOTHING_ALPHA, 0.0)
                val points = RouteJson.fromJson(json)
                if (points.size >= 2) {
                    routePoints.clear()
                    routePoints.addAll(points)
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
                stopSelf()
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

                        if (startLat == 0.0 || startLng == 0.0) {
                            startLat = lastLat
                            startLng = lastLng
                        }

                        if (startLat == 0.0 || startLng == 0.0) {
                            val lm =
                                getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                            try {
                                val lastKnown =
                                    lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                        ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                if (lastKnown != null) {
                                    startLat = lastKnown.latitude
                                    startLng = lastKnown.longitude
                                } else {
                                    startLat = lat
                                    startLng = lng
                                }
                            } catch (_: SecurityException) {
                                startLat = lat
                                startLng = lng
                            }
                        }

                        val points = listOf(RoutePoint(startLat, startLng), RoutePoint(lat, lng))
                        routePoints.clear()
                        routePoints.addAll(points)
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
                    if (lastLat == 0.0 && lastLng == 0.0) {
                        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        try {
                            val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                            if (lastKnown != null) {
                                lastLat = lastKnown.latitude
                                lastLng = lastKnown.longitude
                            }
                        } catch (_: SecurityException) {
                        }
                    }
                    if (!providerReady) {
                        ensureTestProvider()
                    }
                    stopActiveMovement()
                    isJoystickActive = true
                    startJoystickLoop()
                    setRunningFlag(true)
                    broadcastStatus(getString(R.string.status_running), "Joystick Active")
                } else if (!isJoystickActive && wasActive) {
                    stopActiveMovement()
                    setRunningFlag(false)
                    broadcastStatus(getString(R.string.status_idle), "")
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
                updatePlayback()
                handler?.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        })
    }

    private fun stopActiveMovement() {
        isJoystickActive = false
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
        lastLat = latitude
        lastLng = longitude

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
                Log.i(TAG, "pushMockLocation: Successfully pushed to $p ($latitude, $longitude)")
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
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (pauseUntilRealtime > now) {
            lastUpdateRealtime = now
            return
        }

        val dtSeconds = (now - lastUpdateRealtime) / 1000.0
        lastUpdateRealtime = now
        if (dtSeconds <= 0) return

        var remainingMove = currentSegmentSpeed * dtSeconds

        while (remainingMove > 0) {
            val nextIndex = nextSegmentIndex() ?: break
            val start = routePoints[currentSegmentIndex]
            val end = routePoints[nextIndex]
            val segmentLength = haversineMeters(
                start.latitude, start.longitude,
                end.latitude, end.longitude
            )

            val available = segmentLength - distanceOnSegment
            if (available <= 0) {
                currentSegmentIndex = nextIndex
                distanceOnSegment = 0.0
                maybePause()
                currentSegmentSpeed = pickSegmentSpeed()
                continue
            }

            if (remainingMove < available) {
                distanceTraveled += remainingMove
                distanceOnSegment += remainingMove
                val fraction = distanceOnSegment / segmentLength
                val lat = start.latitude + (end.latitude - start.latitude) * fraction
                val lng = start.longitude + (end.longitude - start.longitude) * fraction
                val adjusted = applyRealism(lat, lng)
                pushMockLocation(adjusted.first, adjusted.second)
                broadcastProgress()
                updateNotification()
                return
            }

            distanceTraveled += available
            remainingMove -= available
            currentSegmentIndex = nextIndex
            distanceOnSegment = 0.0
            maybePause()
            currentSegmentSpeed = pickSegmentSpeed()
        }

        handleRouteCompletion()
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

        if (isRoundTripEnabled) {
            isForward = !isForward
            distanceOnSegment = 0.0
            currentSegmentSpeed = pickSegmentSpeed()
            return
        }

        if (isLoopEnabled) {
            currentSegmentIndex = 0
            distanceOnSegment = 0.0
            currentSegmentSpeed = pickSegmentSpeed()
            return
        }

        broadcastStatus(getString(R.string.status_idle), "")
        finishRunHistory(RUN_STATUS_COMPLETED)
        setRunningFlag(false)
        stopSelf()
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
        stopActiveMovement()
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
