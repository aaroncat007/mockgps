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
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

        binding.buttonStop.setOnClickListener {
            stopService(Intent(this, MockLocationService::class.java))
            binding.textStatus.setText(R.string.status_idle)
            binding.textError.text = ""
        }

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

        startMockService()
    }

    private fun startMockService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MockLocationService::class.java)
        )
        binding.textStatus.setText(R.string.status_running)
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
                startMockService()
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
