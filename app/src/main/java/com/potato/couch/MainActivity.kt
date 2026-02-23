package com.potato.couch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PointF
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.Spanned
import android.view.MotionEvent
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.potato.couch.data.AppDatabase
import com.potato.couch.data.RouteEntity
import com.potato.couch.data.RouteJson
import com.potato.couch.data.RoutePoint
import com.potato.couch.databinding.ActivityMainBinding
import com.potato.couch.ui.RouteAdapter
import com.potato.couch.ui.RouteItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private var mapLibre: MapLibreMap? = null

    private val routePoints = mutableListOf<RoutePoint>()
    private lateinit var adapter: RouteAdapter
    private var selectedRouteId: Long? = null
    private var isDraggingPoint = false
    private var draggingIndex = -1

    private val mockStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(MockLocationService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(MockLocationService.EXTRA_MESSAGE).orEmpty()
            binding.textStatus.text = status
            binding.textError.text = message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)
        setupMap()
        updatePointCount()

        adapter = RouteAdapter { item ->
            loadRoute(item)
        }
        binding.recyclerRoutes.layoutManager = LinearLayoutManager(this)
        binding.recyclerRoutes.adapter = adapter

        observeRoutes()
        bindActions()
        setupInputFilters()
        setupTooltips()
        initM4Defaults()
    }

    private fun bindActions() {
        binding.buttonDevOptions.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            startActivity(intent)
        }

        binding.buttonLocationSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }

        binding.buttonStart.setOnClickListener {
            ensurePermissionsAndStart()
        }

        binding.buttonPause.setOnClickListener {
            pauseRoutePlayback()
        }

        binding.buttonStop.setOnClickListener {
            stopRoutePlayback()
        }

        binding.spinnerSpeedMode.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    applySpeedDefaultsIfEmpty(position)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        )

        binding.buttonPresetWalk.setOnClickListener {
            applySpeedPreset(0)
            binding.spinnerSpeedMode.setSelection(0)
        }

        binding.buttonPresetJog.setOnClickListener {
            applySpeedPreset(1)
            binding.spinnerSpeedMode.setSelection(1)
        }

        binding.buttonPresetDrive.setOnClickListener {
            applySpeedPreset(2)
            binding.spinnerSpeedMode.setSelection(2)
        }

        binding.buttonPresetPauseNone.setOnClickListener {
            applyPausePreset(0)
        }

        binding.buttonPresetPauseShort.setOnClickListener {
            applyPausePreset(1)
        }

        binding.buttonPresetPauseLong.setOnClickListener {
            applyPausePreset(2)
        }

        binding.buttonClearPause.setOnClickListener {
            binding.editPauseMin.text?.clear()
            binding.editPauseMax.text?.clear()
        }

        binding.checkDrift.setOnCheckedChangeListener { _, isChecked ->
            binding.editDriftMeters.isEnabled = isChecked
        }

        binding.checkBounce.setOnCheckedChangeListener { _, isChecked ->
            binding.editBounceMeters.isEnabled = isChecked
        }

        binding.checkSmoothing.setOnCheckedChangeListener { _, isChecked ->
            binding.editSmoothingAlpha.isEnabled = isChecked
        }

        binding.checkRoundTrip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.checkLoop.isChecked = false
            }
        }

        attachRangeWatchers()

        binding.buttonSaveRoute.setOnClickListener {
            saveRoute()
        }

        binding.buttonClearRoute.setOnClickListener {
            routePoints.clear()
            updateRouteLine()
            selectedRouteId = null
            binding.editRouteName.text?.clear()
            updatePointCount()
        }

        binding.buttonUndoPoint.setOnClickListener {
            if (routePoints.isNotEmpty()) {
                routePoints.removeAt(routePoints.lastIndex)
                updateRouteLine()
                updatePointCount()
            }
        }

        binding.buttonRenameRoute.setOnClickListener {
            renameSelectedRoute()
        }

        binding.buttonDeleteRoute.setOnClickListener {
            deleteSelectedRoute()
        }
    }

    private fun setupMap() {
        mapView.getMapAsync { map ->
            mapLibre = map
            map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
                style.addSource(GeoJsonSource(POINTS_SOURCE_ID))
                val lineLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(MAP_ROUTE_COLOR),
                    PropertyFactory.lineWidth(4f)
                )
                style.addLayer(lineLayer)

                val circleLayer = org.maplibre.android.style.layers.CircleLayer(
                    POINTS_LAYER_ID,
                    POINTS_SOURCE_ID
                ).withProperties(
                    PropertyFactory.circleColor(MAP_POINT_COLOR),
                    PropertyFactory.circleRadius(4f),
                    PropertyFactory.circleStrokeColor(MAP_POINT_STROKE),
                    PropertyFactory.circleStrokeWidth(1f)
                )
                style.addLayer(circleLayer)
            }

            map.addOnMapClickListener { point ->
                routePoints.add(RoutePoint(point.latitude(), point.longitude()))
                updateRouteLine()
                updatePointCount()
                true
            }

            map.addOnMapLongClickListener { point ->
                if (!tryStartDrag(point.latitude(), point.longitude())) {
                    removeNearestPoint(point.latitude(), point.longitude())
                }
                true
            }
        }

        mapView.setOnTouchListener { _, event ->
            if (!isDraggingPoint) return@setOnTouchListener false
            val map = mapLibre ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val latLng = map.projection.fromScreenLocation(PointF(event.x, event.y))
                    if (draggingIndex in routePoints.indices) {
                        routePoints[draggingIndex] = RoutePoint(latLng.latitude, latLng.longitude)
                        updateRouteLine()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopDrag()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateRouteLine() {
        val map = mapLibre ?: return
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return
        val pointSource = style.getSourceAs<GeoJsonSource>(POINTS_SOURCE_ID) ?: return

        val points = routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }

        if (routePoints.size < 2) {
            source.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(arrayOf()))
        } else {
            source.setGeoJson(LineString.fromLngLats(points))
        }

        val features = points.map { point -> org.maplibre.geojson.Feature.fromGeometry(point) }
        pointSource.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(features))
    }

    private fun loadRoute(item: RouteItem) {
        selectedRouteId = item.id
        routePoints.clear()
        routePoints.addAll(item.points)
        updateRouteLine()
        updatePointCount()
        binding.editRouteName.setText(item.name)
        item.points.firstOrNull()?.let { point ->
            mapLibre?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    org.maplibre.android.geometry.LatLng(point.latitude, point.longitude),
                    15.0
                )
            )
        }
    }

    private fun observeRoutes() {
        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            db.routeDao().getAllRoutes().collectLatest { routes ->
                val items = routes.map { entity ->
                    RouteItem(
                        id = entity.id,
                        name = entity.name,
                        points = RouteJson.fromJson(entity.pointsJson),
                        createdAt = entity.createdAt
                    )
                }
                adapter.submitList(items)
            }
        }
    }

    private fun saveRoute() {
        val name = binding.editRouteName.text.toString().trim()
        if (name.isEmpty()) {
            binding.textError.setText(R.string.error_route_name)
            return
        }
        if (routePoints.size < 2) {
            binding.textError.setText(R.string.error_route_points)
            return
        }

        val entity = RouteEntity(
            name = name,
            pointsJson = RouteJson.toJson(routePoints),
            createdAt = System.currentTimeMillis()
        )

        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            db.routeDao().insert(entity)
        }
        binding.editRouteName.text?.clear()
        binding.textError.text = ""
    }

    private fun renameSelectedRoute() {
        val id = selectedRouteId ?: run {
            binding.textError.setText(R.string.error_route_select)
            return
        }
        val name = binding.editRouteName.text.toString().trim()
        if (name.isEmpty()) {
            binding.textError.setText(R.string.error_route_name)
            return
        }

        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            db.routeDao().rename(id, name)
        }
        binding.textError.text = ""
    }

    private fun deleteSelectedRoute() {
        val id = adapter.getSelectedId() ?: run {
            binding.textError.setText(R.string.error_route_select)
            return
        }
        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            db.routeDao().deleteById(id)
        }
        selectedRouteId = null
        binding.editRouteName.text?.clear()
        routePoints.clear()
        updateRouteLine()
        updatePointCount()
    }

    private fun removeNearestPoint(lat: Double, lng: Double) {
        if (routePoints.isEmpty()) return
        val thresholdMeters = 40.0
        var nearestIndex = -1
        var nearestDistance = Double.MAX_VALUE
        for (i in routePoints.indices) {
            val point = routePoints[i]
            val distance = haversineMeters(lat, lng, point.latitude, point.longitude)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = i
            }
        }
        if (nearestIndex >= 0 && nearestDistance <= thresholdMeters) {
            routePoints.removeAt(nearestIndex)
            updateRouteLine()
            updatePointCount()
        }
    }

    private fun tryStartDrag(lat: Double, lng: Double): Boolean {
        if (routePoints.isEmpty()) return false
        val thresholdMeters = 40.0
        var nearestIndex = -1
        var nearestDistance = Double.MAX_VALUE
        for (i in routePoints.indices) {
            val point = routePoints[i]
            val distance = haversineMeters(lat, lng, point.latitude, point.longitude)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = i
            }
        }
        if (nearestIndex >= 0 && nearestDistance <= thresholdMeters) {
            isDraggingPoint = true
            draggingIndex = nearestIndex
            mapLibre?.uiSettings?.setAllGesturesEnabled(false)
            return true
        }
        return false
    }

    private fun stopDrag() {
        isDraggingPoint = false
        draggingIndex = -1
        mapLibre?.uiSettings?.setAllGesturesEnabled(true)
        updatePointCount()
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

    private fun updatePointCount() {
        binding.textPointCount.text = getString(R.string.label_point_count, routePoints.size)
    }

    private fun ensurePermissionsAndStart() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }

        if (needsNotificationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
            return
        }

        startRoutePlayback()
    }

    private fun startRoutePlayback() {
        if (routePoints.size < 2) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_route_points)
            return
        }
        val speedMode = binding.spinnerSpeedMode.selectedItemPosition
        val speedMinText = binding.editSpeedMin.text.toString().trim()
        val speedMaxText = binding.editSpeedMax.text.toString().trim()
        val pauseMinText = binding.editPauseMin.text.toString().trim()
        val pauseMaxText = binding.editPauseMax.text.toString().trim()
        val randomSpeed = binding.checkRandomSpeed.isChecked
        val loopEnabled = binding.checkLoop.isChecked
        val roundTripEnabled = binding.checkRoundTrip.isChecked
        val driftEnabled = binding.checkDrift.isChecked
        val bounceEnabled = binding.checkBounce.isChecked
        val smoothingEnabled = binding.checkSmoothing.isChecked

        val driftMeters = binding.editDriftMeters.text.toString().toDoubleOrNull() ?: 0.0
        val bounceMeters = binding.editBounceMeters.text.toString().toDoubleOrNull() ?: 0.0
        val smoothingAlpha = binding.editSmoothingAlpha.text.toString().toDoubleOrNull() ?: 0.0

        if (!validateRanges(showError = true)) {
            return
        }

        if (driftEnabled && !isInRange(driftMeters, 0.0, MAX_DRIFT_METERS)) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_drift_range)
            binding.editDriftMeters.error = getString(R.string.error_drift_range)
            return
        }
        if (bounceEnabled && !isInRange(bounceMeters, 0.0, MAX_BOUNCE_METERS)) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_bounce_range)
            binding.editBounceMeters.error = getString(R.string.error_bounce_range)
            return
        }
        if (smoothingEnabled && !isInRange(smoothingAlpha, 0.0, MAX_SMOOTHING_ALPHA)) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_smoothing_range)
            binding.editSmoothingAlpha.error = getString(R.string.error_smoothing_range)
            return
        }

        val speedRange = normalizeRange(speedMinText, speedMaxText, MAX_SPEED_KMH)
        if (speedRange == null) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_speed_range)
            return
        }

        val pauseRange = normalizeRange(pauseMinText, pauseMaxText, MAX_PAUSE_SEC)
        if (pauseRange == null) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_pause_range)
            return
        }
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
            putExtra(MockLocationService.EXTRA_ROUTE_JSON, RouteJson.toJson(routePoints))
            putExtra(MockLocationService.EXTRA_SPEED_MODE, speedMode)
            putExtra(MockLocationService.EXTRA_SPEED_MIN_KMH, speedRange.first)
            putExtra(MockLocationService.EXTRA_SPEED_MAX_KMH, speedRange.second)
            putExtra(MockLocationService.EXTRA_PAUSE_MIN_SEC, pauseRange.first)
            putExtra(MockLocationService.EXTRA_PAUSE_MAX_SEC, pauseRange.second)
            putExtra(MockLocationService.EXTRA_RANDOM_SPEED, randomSpeed)
            putExtra(MockLocationService.EXTRA_LOOP_ENABLED, loopEnabled)
            putExtra(MockLocationService.EXTRA_ROUNDTRIP_ENABLED, roundTripEnabled)
            putExtra(MockLocationService.EXTRA_DRIFT_ENABLED, driftEnabled)
            putExtra(MockLocationService.EXTRA_BOUNCE_ENABLED, bounceEnabled)
            putExtra(MockLocationService.EXTRA_SMOOTHING_ENABLED, smoothingEnabled)
            putExtra(MockLocationService.EXTRA_DRIFT_METERS, driftMeters)
            putExtra(MockLocationService.EXTRA_BOUNCE_METERS, bounceMeters)
            putExtra(MockLocationService.EXTRA_SMOOTHING_ALPHA, smoothingAlpha)
        }
        ContextCompat.startForegroundService(this, intent)
        binding.textStatus.setText(R.string.status_running)
        binding.textError.text = ""
    }

    private fun normalizeRange(minText: String, maxText: String, maxAllowed: Double? = null): Pair<Double, Double>? {
        if (minText.isEmpty() && maxText.isEmpty()) return 0.0 to 0.0
        val min = if (minText.isEmpty()) null else minText.toDoubleOrNull()
        val max = if (maxText.isEmpty()) null else maxText.toDoubleOrNull()
        if (min == null && max == null) return null
        val safeMin = min ?: max ?: return null
        val safeMax = max ?: min ?: return null
        if (safeMin < 0.0 || safeMax < 0.0) return null
        if (safeMax < safeMin) return null
        if (maxAllowed != null && (safeMin > maxAllowed || safeMax > maxAllowed)) return null
        return safeMin to safeMax
    }

    private fun applySpeedDefaultsIfEmpty(mode: Int) {
        val minText = binding.editSpeedMin.text.toString().trim()
        val maxText = binding.editSpeedMax.text.toString().trim()
        if (minText.isNotEmpty() || maxText.isNotEmpty()) return
        val (min, max) = when (mode) {
            1 -> 6.0 to 10.0   // Jog
            2 -> 30.0 to 60.0  // Drive
            else -> 3.0 to 5.0 // Walk
        }
        binding.editSpeedMin.setText(min.toString())
        binding.editSpeedMax.setText(max.toString())
    }

    private fun applySpeedPreset(mode: Int) {
        val (min, max) = when (mode) {
            1 -> 6.0 to 10.0   // Jog
            2 -> 30.0 to 60.0  // Drive
            else -> 3.0 to 5.0 // Walk
        }
        binding.editSpeedMin.setText(min.toString())
        binding.editSpeedMax.setText(max.toString())
    }

    private fun applyPausePreset(mode: Int) {
        val (min, max) = when (mode) {
            1 -> 2.0 to 5.0   // Short
            2 -> 8.0 to 15.0  // Long
            else -> 0.0 to 0.0 // No pause
        }
        binding.editPauseMin.setText(min.toString())
        binding.editPauseMax.setText(max.toString())
    }

    private fun setupInputFilters() {
        val filter = DecimalInputFilter()
        binding.editSpeedMin.filters = arrayOf(filter)
        binding.editSpeedMax.filters = arrayOf(filter)
        binding.editPauseMin.filters = arrayOf(filter)
        binding.editPauseMax.filters = arrayOf(filter)
        binding.editDriftMeters.filters = arrayOf(filter)
        binding.editBounceMeters.filters = arrayOf(filter)
        binding.editSmoothingAlpha.filters = arrayOf(filter)
    }

    private fun setupTooltips() {
        ViewCompat.setTooltipText(binding.iconSpeedInfo, getString(R.string.tooltip_speed_range))
        ViewCompat.setTooltipText(binding.iconPauseInfo, getString(R.string.tooltip_pause_range))
    }

    private fun initM4Defaults() {
        binding.checkDrift.isChecked = false
        binding.checkBounce.isChecked = false
        binding.checkSmoothing.isChecked = false
        binding.editDriftMeters.isEnabled = false
        binding.editBounceMeters.isEnabled = false
        binding.editSmoothingAlpha.isEnabled = false
        binding.editDriftMeters.setText("3")
        binding.editBounceMeters.setText("5")
        binding.editSmoothingAlpha.setText("0.3")
    }

    private fun attachRangeWatchers() {
        val watcher = SimpleTextWatcher {
            validateRanges(showError = false)
        }
        binding.editSpeedMin.addTextChangedListener(watcher)
        binding.editSpeedMax.addTextChangedListener(watcher)
        binding.editPauseMin.addTextChangedListener(watcher)
        binding.editPauseMax.addTextChangedListener(watcher)
    }

    private fun validateRanges(showError: Boolean): Boolean {
        val speedRange = normalizeRange(
            binding.editSpeedMin.text.toString().trim(),
            binding.editSpeedMax.text.toString().trim(),
            MAX_SPEED_KMH
        )
        if (speedRange == null) {
            if (showError) {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_speed_range)
                setRangeError(binding.editSpeedMin, binding.editSpeedMax, true, R.string.error_speed_range)
            }
            return false
        } else {
            setRangeError(binding.editSpeedMin, binding.editSpeedMax, false, R.string.error_speed_range)
        }

        val pauseRange = normalizeRange(
            binding.editPauseMin.text.toString().trim(),
            binding.editPauseMax.text.toString().trim(),
            MAX_PAUSE_SEC
        )
        if (pauseRange == null) {
            if (showError) {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_pause_range)
                setRangeError(binding.editPauseMin, binding.editPauseMax, true, R.string.error_pause_range)
            }
            return false
        } else {
            setRangeError(binding.editPauseMin, binding.editPauseMax, false, R.string.error_pause_range)
        }

        if (!showError) {
            binding.textError.text = ""
        }
        return true
    }

    private fun isInRange(value: Double, min: Double, max: Double): Boolean {
        return value >= min && value <= max
    }

    private fun setRangeError(
        minField: EditText,
        maxField: EditText,
        hasError: Boolean,
        messageRes: Int
    ) {
        if (hasError) {
            val message = getString(messageRes)
            minField.error = message
            maxField.error = message
        } else {
            minField.error = null
            maxField.error = null
        }
    }

    private class DecimalInputFilter : InputFilter {
        private val pattern = Regex("^\\d*(\\.\\d{0,2})?$")

        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val newValue = StringBuilder(dest)
                .replace(dstart, dend, source.subSequence(start, end).toString())
                .toString()
            return if (pattern.matches(newValue)) null else ""
        }
    }

    private class SimpleTextWatcher(private val onChange: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = onChange()
    }

    private fun pauseRoutePlayback() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_PAUSE_ROUTE
        }
        startService(intent)
    }

    private fun stopRoutePlayback() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP_ROUTE
        }
        startService(intent)
        stopService(Intent(this, MockLocationService::class.java))
        binding.textStatus.setText(R.string.status_idle)
        binding.textError.text = ""
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        registerReceiver(
            mockStatusReceiver,
            IntentFilter(MockLocationService.ACTION_MOCK_STATUS)
        )
        syncStatusFromPrefs()
        if (!isLocationEnabled()) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_location_disabled)
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        unregisterReceiver(mockStatusReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensurePermissionsAndStart()
            } else {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_no_permission)
            }
        }

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRoutePlayback()
            } else {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_notifications)
            }
        }
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun syncStatusFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isRunning = prefs.getBoolean(PREF_KEY_RUNNING, false)
        binding.textStatus.setText(if (isRunning) R.string.status_running else R.string.status_idle)
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
        private const val PREFS_NAME = "mock_prefs"
        private const val PREF_KEY_RUNNING = "mock_service_running"
        private const val MAX_SPEED_KMH = 200.0
        private const val MAX_PAUSE_SEC = 120.0
        private const val MAX_DRIFT_METERS = 50.0
        private const val MAX_BOUNCE_METERS = 50.0
        private const val MAX_SMOOTHING_ALPHA = 1.0
        private const val MAP_STYLE_URL = "https://demotiles.maplibre.org/style.json"
        private const val ROUTE_SOURCE_ID = "route-source"
        private const val ROUTE_LAYER_ID = "route-layer"
        private const val POINTS_SOURCE_ID = "points-source"
        private const val POINTS_LAYER_ID = "points-layer"
        private const val MAP_ROUTE_COLOR = "#1E88E5"
        private const val MAP_POINT_COLOR = "#FF7043"
        private const val MAP_POINT_STROKE = "#FFFFFF"
    }
}
