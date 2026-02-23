package com.potato.couch

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
import androidx.core.app.NotificationCompat
import com.potato.couch.data.AppDatabase
import com.potato.couch.data.GpsEventEntity
import com.potato.couch.data.RouteJson
import com.potato.couch.data.RoutePoint
import com.potato.couch.data.RunHistoryEntity
import java.util.concurrent.Executors

class MockLocationService : Service() {

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var locationManager: LocationManager? = null
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
    private var bouncePhase = 0.0
    private var runId: Long = 0
    private val db by lazy { AppDatabase.getInstance(this) }
    private val dbExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startInForeground()
        startRouteLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                    setRunningFlag(true)
                    broadcastStatus(getString(R.string.status_running), "")
                    insertRunHistory(points.size, speedMode)
                }
            }
            ACTION_PAUSE_ROUTE -> {
                isPaused = !isPaused
                broadcastStatus(
                    if (isPaused) getString(R.string.status_paused) else getString(R.string.status_running),
                    ""
                )
            }
            ACTION_STOP_ROUTE -> {
                routePoints.clear()
                currentSegmentIndex = 0
                distanceOnSegment = 0.0
                isPaused = false
                finishRunHistory(RUN_STATUS_STOPPED)
                setRunningFlag(false)
                broadcastStatus(getString(R.string.status_idle), "")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRouteLoop()
        removeTestProvider()
        finishRunHistory(RUN_STATUS_STOPPED)
        setRunningFlag(false)
        broadcastStatus(getString(R.string.status_idle), "")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channelId = "mock_location"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Mock Location",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Mocking location is running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun startRouteLoop() {
        if (handlerThread != null) return

        ensureTestProvider()

        handlerThread = HandlerThread("mock-location-thread").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        handler?.post(object : Runnable {
            override fun run() {
                updatePlayback()
                handler?.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        })
    }

    private fun stopRouteLoop() {
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    @Suppress("DEPRECATION")
    private fun ensureTestProvider() {
        val provider = LocationManager.GPS_PROVIDER
        val lm = locationManager ?: return
        try {
            lm.addTestProvider(
                provider,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                0,
                5
            )
        } catch (_: IllegalArgumentException) {
            // Provider already exists.
        }

        try {
            lm.setTestProviderEnabled(provider, true)
        } catch (_: SecurityException) {
            // If not set as mock location app, this will fail.
            reportMockAppError()
        }
    }

    @Suppress("DEPRECATION")
    private fun removeTestProvider() {
        val lm = locationManager ?: return
        try {
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }
    }

    private fun pushMockLocation(latitude: Double, longitude: Double) {
        val lm = locationManager ?: return
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        try {
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
            logGpsEvent(location)
        } catch (_: SecurityException) {
            // Not authorized as mock location app.
            reportMockAppError()
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
                distanceOnSegment += remainingMove
                val fraction = distanceOnSegment / segmentLength
                val lat = start.latitude + (end.latitude - start.latitude) * fraction
                val lng = start.longitude + (end.longitude - start.longitude) * fraction
                val adjusted = applyRealism(lat, lng)
                pushMockLocation(adjusted.first, adjusted.second)
                return
            }

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

    private fun insertRunHistory(pointCount: Int, speedMode: Int) {
        val now = System.currentTimeMillis()
        val entity = RunHistoryEntity(
            routeName = null,
            pointCount = pointCount,
            speedMode = speedMode,
            loopEnabled = isLoopEnabled,
            roundTripEnabled = isRoundTripEnabled,
            startedAt = now,
            status = RUN_STATUS_RUNNING
        )
        dbExecutor.execute {
            runId = db.runHistoryDao().insert(entity)
        }
    }

    private fun finishRunHistory(status: String) {
        if (runId <= 0) return
        val end = System.currentTimeMillis()
        val id = runId
        runId = 0
        dbExecutor.execute {
            db.runHistoryDao().updateEnd(id, end, status)
        }
    }

    private fun logGpsEvent(location: Location) {
        if (runId <= 0) return
        val event = GpsEventEntity(
            runId = runId,
            timestamp = System.currentTimeMillis(),
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            speedMps = currentSegmentSpeed
        )
        dbExecutor.execute {
            db.gpsEventDao().insert(event)
        }
    }

    private fun broadcastStatus(status: String, message: String) {
        val intent = Intent(ACTION_MOCK_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun setRunningFlag(isRunning: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_RUNNING, isRunning).apply()
    }

    companion object {
        const val ACTION_MOCK_STATUS = "com.potato.couch.MOCK_STATUS"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_MESSAGE = "extra_message"
        const val ACTION_START_ROUTE = "com.potato.couch.ACTION_START_ROUTE"
        const val ACTION_PAUSE_ROUTE = "com.potato.couch.ACTION_PAUSE_ROUTE"
        const val ACTION_STOP_ROUTE = "com.potato.couch.ACTION_STOP_ROUTE"
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
        private const val RUN_STATUS_RUNNING = "RUNNING"
        private const val RUN_STATUS_COMPLETED = "COMPLETED"
        private const val RUN_STATUS_STOPPED = "STOPPED"
        private const val PREFS_NAME = "mock_prefs"
        private const val PREF_KEY_RUNNING = "mock_service_running"
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val SPEED_MODE_JOG = 1
        private const val SPEED_MODE_DRIVE = 2
        private const val SPEED_WALK = 1.4
        private const val SPEED_JOG = 2.8
        private const val SPEED_DRIVE = 13.9
    }
}
