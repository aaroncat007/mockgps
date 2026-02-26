package com.sofawander.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.Bitmap
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.Spanned
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import com.sofawander.app.data.AppDatabase
import com.sofawander.app.data.FavoriteEntity
import com.sofawander.app.data.RouteEntity
import com.sofawander.app.data.RouteFileIO
import com.sofawander.app.data.RouteJson
import com.sofawander.app.data.RoutePoint
import com.sofawander.app.databinding.ActivityMainBinding
import com.sofawander.app.ui.FavoriteAdapter
import com.sofawander.app.ui.FavoriteItem
import com.sofawander.app.ui.GpsEventAdapter
import com.sofawander.app.ui.GpsEventItem
import com.sofawander.app.ui.RunHistoryAdapter
import com.sofawander.app.ui.RunHistoryItem
import com.sofawander.app.ui.RouteAdapter
import com.sofawander.app.ui.RouteItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.BitmapUtils
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.gson.Gson
import com.google.android.gms.location.LocationServices
import android.graphics.drawable.Drawable
import android.view.inputmethod.EditorInfo
import android.location.Geocoder
import android.util.Log
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private var mapLibre: MapLibreMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val routePoints = mutableListOf<RoutePoint>()
    private lateinit var adapter: RouteAdapter
    private var selectedRouteId: Long? = null
    private lateinit var favoriteAdapter: FavoriteAdapter
    private lateinit var historyAdapter: RunHistoryAdapter
    private var selectedFavoriteId: Long? = null
    private var selectedPointSource: GeoJsonSource? = null
    private var lastTappedPoint: RoutePoint? = null
    private var hasSelectedPin: Boolean = false
    private var currentMockLat: Double = 0.0
    private var currentMockLng: Double = 0.0
    private var isCameraLocked: Boolean = false
    private var backPressedTime: Long = 0L

    private fun isInvalidLocation(lat: Double, lng: Double): Boolean {
        return kotlin.math.abs(lat) < 0.0001 && kotlin.math.abs(lng) < 0.0001
    }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val input = contentResolver.openInputStream(uri) ?: return@registerForActivityResult
            input.use { raw ->
                val stream = java.io.BufferedInputStream(raw)
                stream.mark(512)
                try {
                    val buffer = ByteArray(512)
                    val read = stream.read(buffer)
                    val textSample = if (read > 0) String(buffer, 0, read, Charsets.UTF_8) else ""
                    stream.reset()
                    val points = when {
                        textSample.contains("<gpx", ignoreCase = true) -> RouteFileIO.parseGpx(stream)
                        textSample.contains("<kml", ignoreCase = true) -> RouteFileIO.parseKml(stream)
                        else -> null
                    }
                    if (points == null) {
                        Toast.makeText(this@MainActivity, R.string.error_import_unsupported, Toast.LENGTH_SHORT).show()
                        return@use
                    }
                    if (points.isNotEmpty()) {
                        routePoints.clear()
                        routePoints.addAll(points)
                        updateRouteLine()
                        updateRouteStats()
                        showImportSummary(points.size)
                    } else {
                        Toast.makeText(this@MainActivity, R.string.error_import_empty, Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, R.string.error_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val createGpxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            if (uri == null) return@registerForActivityResult
            if (routePoints.size < 2) {
                Toast.makeText(this, R.string.error_route_export_points, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val output = contentResolver.openOutputStream(uri) ?: run {
                Toast.makeText(this, R.string.error_export_failed, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            output.use { stream ->
                try {
                    val name = "SofaWander Route"
                    RouteFileIO.writeGpx(stream, name, routePoints)
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.error_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val createKmlLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri ->
            if (uri == null) return@registerForActivityResult
            if (routePoints.size < 2) {
                Toast.makeText(this, R.string.error_route_export_points, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val output = contentResolver.openOutputStream(uri) ?: run {
                Toast.makeText(this, R.string.error_export_failed, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            output.use { stream ->
                try {
                    val name = "SofaWander Route"
                    RouteFileIO.writeKml(stream, name, routePoints)
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.error_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    private var isDraggingPoint = false
    private var draggingIndex = -1
    private var isRouteRunning = false

    private val mockStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(MockLocationService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(MockLocationService.EXTRA_MESSAGE).orEmpty()
            android.util.Log.d("MockApp", "Status received: $status")
            binding.textStatus.text = status
            if (message.isNotEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            val running = status == getString(R.string.status_running) || status == getString(R.string.status_paused)
            isRouteRunning = running
            binding.buttonStartRoute.text = if (running) getString(R.string.button_stop) else getString(R.string.button_start)
            binding.layoutPlaybackStats.visibility = if (running) View.VISIBLE else View.GONE

            if (status == getString(R.string.status_paused)) {
                binding.btnPlaybackPause.setImageResource(R.drawable.ic_play)
            } else {
                binding.btnPlaybackPause.setImageResource(R.drawable.ic_pause)
            }
        }
    }

    private val mockProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val traveled = intent.getDoubleExtra(MockLocationService.EXTRA_DISTANCE_TRAVELED, 0.0)
            val total = intent.getDoubleExtra(MockLocationService.EXTRA_TOTAL_DISTANCE, 0.0)
            val elapsedMs = intent.getLongExtra(MockLocationService.EXTRA_ELAPSED_MS, 0L)
            val speedKmh = intent.getDoubleExtra(MockLocationService.EXTRA_CURRENT_SPEED_KMH, 0.0)
            
            // 獲取目前點位置（用於跳轉對話框距離顯示）
            val lat = intent.getDoubleExtra(MockLocationService.EXTRA_LAT, 0.0)
            val lng = intent.getDoubleExtra(MockLocationService.EXTRA_LNG, 0.0)
            if (!isInvalidLocation(lat, lng)) {
                currentMockLat = lat
                currentMockLng = lng
                
                // Persistence update
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                prefs.edit()
                    .putFloat("pref_last_mock_lat", lat.toFloat())
                    .putFloat("pref_last_mock_lng", lng.toFloat())
                    .apply()
            }

            binding.textPlaybackDistance.text = formatDistance(traveled) + " / " + formatDistance(total)

            val elapsedStr = formatDuration(elapsedMs)
            
            var etaMs = 0L
            if (traveled > 0 && total > traveled && elapsedMs > 0) {
                val currentPace = traveled / elapsedMs
                if (currentPace > 0.0000001) { // 避免除以趨近於零造成溢位
                    etaMs = ((total - traveled) / currentPace).toLong()
                    if (etaMs > 86400000L * 7) etaMs = 86400000L * 7 // 如果預計時間超過 7 天，強制設為上限
                }
            }
            
            val etaStr = if (etaMs > 0) formatDuration(elapsedMs + etaMs) else "--:--"
            
            if (total <= 0 || etaMs == 0L) {
                binding.textPlaybackTime.text = elapsedStr
            } else {
                binding.textPlaybackTime.text = "$elapsedStr / $etaStr"
            }

            binding.textPlaybackSpeed.text = "%.1f km/h".format(speedKmh)
            
            // 確保行走跳轉時也會顯示底部統計條
            if (binding.layoutPlaybackStats.visibility != View.VISIBLE) {
                android.util.Log.d("MockApp", "Showing stats bar via progress update")
                binding.layoutPlaybackStats.visibility = View.VISIBLE
            }

            // 相機鎖定跟隨邏輯
            if (isCameraLocked && !isInvalidLocation(lat, lng)) {
                mapLibre?.animateCamera(
                    CameraUpdateFactory.newLatLng(
                         org.maplibre.android.geometry.LatLng(lat, lng)
                    )
                )
            }
        }
    }

    private val historyLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data?.getLongExtra("HISTORY_ID", -1L) ?: -1L
            val action = result.data?.getStringExtra("HISTORY_ACTION") ?: "REPLAY"
            if (id != -1L) {
                if (action == "LOAD") {
                    loadHistoryAsRoute(id)
                } else {
                    loadAndReplayHistory(id)
                }
            }
        }
    }

    private fun loadHistoryAsRoute(historyId: Long) {
        lifecycleScope.launch {
            val history = AppDatabase.getInstance(this@MainActivity)
                .runHistoryDao().getById(historyId) ?: return@launch
            
            val points = try {
                com.sofawander.app.data.RouteJson.fromJson(history.routePointsJson)
            } catch (e: Exception) {
                emptyList()
            }
            
            if (points.isNotEmpty()) {
                routePoints.clear()
                routePoints.addAll(points)
                updateRouteLine()
                updateRouteStats()
                binding.layoutRouteEditor.visibility = View.VISIBLE
                binding.buttonStartRoute.text = getString(R.string.button_start)
                Toast.makeText(this@MainActivity, "Loaded history: ${history.routeName ?: "Route"}", Toast.LENGTH_SHORT).show()
            }
        }
    }



    private val routeListLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data?.getLongExtra("EXTRA_ROUTE_ID", -1L) ?: -1L
            if (id != -1L) {
                // Find route from DB by ID
                lifecycleScope.launch {
                    val entity = com.sofawander.app.data.AppDatabase.getInstance(this@MainActivity).routeDao().getRouteById(id)
                    if (entity != null) {
                        loadRoute(entity)
                    }
                }
            }
        }
    }

    private val favoriteListLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val lat = result.data?.getDoubleExtra("EXTRA_FAV_LAT", 0.0) ?: 0.0
            val lng = result.data?.getDoubleExtra("EXTRA_FAV_LNG", 0.0) ?: 0.0
            val id = result.data?.getLongExtra("EXTRA_FAV_ID", -1L) ?: -1L
            val action = result.data?.getStringExtra("EXTRA_ACTION") ?: "SHOW_MAP"
            if (lat != 0.0 && lng != 0.0) {
                selectedFavoriteId = id
                lastTappedPoint = RoutePoint(lat, lng)
                val pt = org.maplibre.android.geometry.LatLng(lat, lng)
                mapLibre?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(pt, 15.5)
                )
                updateSelectedPointSource(pt)
                if (action == "TELEPORT") {
                    showTeleportDialog(pt)
                }
            }
        }
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000) "%.2f km".format(meters / 1000.0)
        else "%.0f m".format(meters)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 雙擊返回鍵退出邏輯
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 若側邊選單開著，則先關閉
                if (binding.drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
                    binding.drawerLayout.closeDrawers()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - backPressedTime < 2000L) {
                    finishAndRemoveTask()
                } else {
                    backPressedTime = now
                    Toast.makeText(this@MainActivity, R.string.toast_back_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        adapter = RouteAdapter { item ->
            routePoints.clear()
            routePoints.addAll(item.points)
            updateRouteLine()
            updateRouteStats()
            selectedRouteId = item.id
            if (routePoints.isNotEmpty()) {
                val pt = routePoints.first()
                mapLibre?.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(pt.latitude, pt.longitude),
                        15.0
                    )
                )
            }
        }
        favoriteAdapter = FavoriteAdapter(
            onClick = { item -> showFavoriteEditDialog(item) },
            onDelete = { item ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@MainActivity).favoriteDao().deleteById(item.id)
                }
            }
        )
        historyAdapter = RunHistoryAdapter { item -> showRunDetails(item) }

        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)
        setupMap()
        updateRouteStats()
        
        // Initialize mock coordinates from persistence
        val commonPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        currentMockLat = commonPrefs.getFloat("pref_last_mock_lat", 0f).toDouble()
        currentMockLng = commonPrefs.getFloat("pref_last_mock_lng", 0f).toDouble()

        observeFavorites()
        observeHistory()
        bindActions()
        setupJoystick()

        // 註冊 MockLocationService 狀態接收器
        val statusFilter = android.content.IntentFilter(MockLocationService.ACTION_MOCK_STATUS)
        val progressFilter = android.content.IntentFilter(MockLocationService.ACTION_MOCK_PROGRESS)
        val routeFilter = android.content.IntentFilter(MockLocationService.ACTION_ROUTE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mockStatusReceiver, statusFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(mockProgressReceiver, progressFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(mockRouteReceiver, routeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mockStatusReceiver, statusFilter)
            registerReceiver(mockProgressReceiver, progressFilter)
            registerReceiver(mockRouteReceiver, routeFilter)
        }

        // Android 13+ 通知權限與定位權限請求
        checkInitialPermissions()
    }

    private fun checkInitialPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun bindActions() {
        binding.btnLocation.setOnClickListener {
            centerToCurrentLocation()
        }

        binding.btnTeleport.setOnClickListener {
            val point = if (hasSelectedPin && lastTappedPoint != null) {
                org.maplibre.android.geometry.LatLng(lastTappedPoint!!.latitude, lastTappedPoint!!.longitude)
            } else {
                null
            }
            showTeleportDialog(point)
        }

        binding.btnSearch.setOnClickListener {
            performSearch()
        }

        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        binding.btnFavorites.setOnClickListener {
            favoriteListLauncher.launch(Intent(this, com.sofawander.app.ui.FavoriteListActivity::class.java))
        }

        binding.btnWalkMenu.setOnClickListener {
            val visible = binding.layoutWalkControls.visibility == View.VISIBLE
            binding.layoutWalkControls.visibility = if (visible) View.GONE else View.VISIBLE
        }

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        
        binding.btnWalkSpeed.setOnClickListener {
            val speed = prefs.getString("pref_speed_walk", "9.0")?.toDoubleOrNull() ?: 9.0
            applySpeedPreset(speed, "walk")
            binding.layoutWalkControls.visibility = View.GONE
        }
        binding.btnJogSpeed.setOnClickListener {
            val speed = prefs.getString("pref_speed_jog", "14.4")?.toDoubleOrNull() ?: 14.4
            applySpeedPreset(speed, "jog")
            binding.layoutWalkControls.visibility = View.GONE
        }
        binding.btnRunSpeed.setOnClickListener {
            val speed = prefs.getString("pref_speed_drive", "50.0")?.toDoubleOrNull() ?: 50.0
            applySpeedPreset(speed, "drive")
            binding.layoutWalkControls.visibility = View.GONE
        }

        binding.buttonStartRoute.setOnClickListener {
            if (isRouteRunning) {
                stopRoutePlayback()
            } else {
                ensurePermissionsAndStart()
            }
        }

        binding.btnPlaybackPause.setOnClickListener {
            val intent = Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_PAUSE_ROUTE
            }
            startService(intent)
        }

        binding.btnPlaybackStop.setOnClickListener {
            stopRoutePlayback()
        }

        binding.buttonLoadRoute.setOnClickListener {
            routeListLauncher.launch(Intent(this, com.sofawander.app.ui.RouteListActivity::class.java))
        }

        binding.buttonSaveRoute.setOnClickListener {
            saveRoute()
        }

        binding.buttonClearRoute.setOnClickListener {
            routePoints.clear()
            selectedRouteId = null
            updateRouteLine()
            updateRouteStats()
        }

        binding.buttonUndoPoint.setOnClickListener {
            if (routePoints.isNotEmpty()) {
                routePoints.removeAt(routePoints.lastIndex)
                updateRouteLine()
                updateRouteStats()
            }
        }

        binding.buttonStartRoute.setOnClickListener {
            if (isRouteRunning) {
                stopRoutePlayback()
                isRouteRunning = false
                binding.buttonStartRoute.text = "Start Route"
            } else {
                ensurePermissionsAndStart()
                // isRouteRunning 將在收到 STATUS_RUNNING 廣播後設定
            }
        }

        binding.btnRouteEditorClose.setOnClickListener {
            binding.layoutRouteEditor.visibility = android.view.View.GONE
        }

        binding.btnRoutePlanning.setOnClickListener {
            val visible = binding.layoutRouteEditor.visibility == android.view.View.VISIBLE
            binding.layoutRouteEditor.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
        }

        binding.btnSettings.setOnClickListener {
            binding.drawerLayout.open()
        }

        binding.menuHome.setOnClickListener {
            binding.drawerLayout.close()
        }

        binding.menuSettings.setOnClickListener {
            binding.drawerLayout.close()
            startActivity(Intent(this, com.sofawander.app.ui.SettingsActivity::class.java))
        }

        binding.menuHistory.setOnClickListener {
            binding.drawerLayout.close()
            historyLauncher.launch(Intent(this, com.sofawander.app.ui.HistoryActivity::class.java))
        }

        binding.menuRouteList.setOnClickListener {
            binding.drawerLayout.close()
            routeListLauncher.launch(Intent(this, com.sofawander.app.ui.RouteListActivity::class.java))
        }

        binding.menuFavorites.setOnClickListener {
            binding.drawerLayout.close()
            favoriteListLauncher.launch(Intent(this, com.sofawander.app.ui.FavoriteListActivity::class.java))
        }

        binding.menuDevOptions.setOnClickListener {
            binding.drawerLayout.close()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.error_dev_options, Toast.LENGTH_SHORT).show()
            }
        }

        binding.menuExit.setOnClickListener {
            binding.drawerLayout.close()
            finishAndRemoveTask()
        }

        binding.btnWalkMenu.setOnClickListener {
            val visible = binding.layoutWalkControls.visibility == android.view.View.VISIBLE
            binding.layoutWalkControls.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
        }

        binding.btnTeleport.setOnClickListener {
            showTeleportDialog()
        }

        binding.btnLocation.setOnClickListener {
            centerToCurrentLocation()
        }

        binding.btnSearch.setOnClickListener {
            performSearch()
        }

        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        binding.btnViewOrigin.setOnClickListener {
            if (routePoints.isNotEmpty()) {
                val start = routePoints[0]
                mapLibre?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(start.latitude, start.longitude),
                        16.0
                    )
                )
            } else if (!isInvalidLocation(currentMockLat, currentMockLng)) {
                mapLibre?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(currentMockLat, currentMockLng),
                        16.0
                    )
                )
            }
        }

        binding.btnLock.setOnClickListener {
            isCameraLocked = !isCameraLocked
            if (isCameraLocked) {
                binding.btnLock.setColorFilter(getColor(R.color.blue_500))
                if (!isInvalidLocation(currentMockLat, currentMockLng)) {
                    mapLibre?.animateCamera(
                        CameraUpdateFactory.newLatLng(
                            org.maplibre.android.geometry.LatLng(currentMockLat, currentMockLng)
                        )
                    )
                }
            } else {
                binding.btnLock.clearColorFilter()
            }
        }
    }

    private fun setupMap() {
        mapView.getMapAsync { map ->
            mapLibre = map
            map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                setupMapLayers(style)
                enableLocationComponent(style)
            }

            map.addOnMapClickListener { point ->
                if (binding.layoutRouteEditor.visibility == android.view.View.VISIBLE) {
                    routePoints.add(RoutePoint(point.latitude, point.longitude))
                    lastTappedPoint = RoutePoint(point.latitude, point.longitude)
                    updateRouteLine()
                    updateRouteStats()
                } else {
                    // 非編輯模式下，點擊地圖即彈出跳轉
                    updateSelectedPointSource(point)
                    showTeleportDialog(point)
                }
                true
            }

            map.addOnCameraMoveStartedListener { reason ->
                if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    if (isCameraLocked) {
                        isCameraLocked = false
                        binding.btnLock.clearColorFilter()
                        android.util.Log.d("MockApp", "Camera unlocked due to user gesture")
                    }
                }
            }

            map.addOnMapLongClickListener { point ->
                if (binding.layoutRouteEditor.visibility == android.view.View.VISIBLE) {
                    if (!tryStartDrag(point.latitude, point.longitude)) {
                        removeNearestPoint(point.latitude, point.longitude)
                    }
                }
                true
            }

            // Load cached camera position for zero-delay startup centering
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            val lastLat = prefs.getFloat("pref_last_lat", 0f).toDouble()
            val lastLng = prefs.getFloat("pref_last_lng", 0f).toDouble()
            val lastZoom = prefs.getFloat("pref_last_zoom", 15f).toDouble()
            
            if (lastLat != 0.0 || lastLng != 0.0) {
                map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    org.maplibre.android.camera.CameraPosition.Builder()
                        .target(org.maplibre.android.geometry.LatLng(lastLat, lastLng))
                        .zoom(lastZoom)
                        .build()
                ))
            }

            centerToCurrentLocation()
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

    private var joystickCenterX = 0f
    private var joystickCenterY = 0f
    private var joystickRadius = 0f

    private fun setupJoystick() {
        val container = binding.joystickContainer
        val thumb = binding.joystickThumb

        container.post {
            joystickCenterX = container.width / 2f
            joystickCenterY = container.height / 2f
            joystickRadius = container.width / 2f - thumb.width / 2f
            
            thumb.translationX = 0f
            thumb.translationY = 0f
        }

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - joystickCenterX
                    val dy = event.y - joystickCenterY
                    val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                    if (distance <= joystickRadius) {
                        thumb.translationX = dx
                        thumb.translationY = dy
                    } else {
                        val ratio = joystickRadius / distance
                        thumb.translationX = dx * ratio
                        thumb.translationY = dy * ratio
                    }
                    
                    val intent = Intent(this@MainActivity, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_JOYSTICK_MOVE
                        // Normalize to -1.0 .. 1.0 based on radius
                        val normDx = if (joystickRadius > 0) thumb.translationX / joystickRadius else 0f
                        val normDy = if (joystickRadius > 0) thumb.translationY / joystickRadius else 0f
                        putExtra(MockLocationService.EXTRA_JOYSTICK_DX, normDx)
                        putExtra(MockLocationService.EXTRA_JOYSTICK_DY, normDy)
                    }
                    startService(intent)

                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    thumb.animate().translationX(0f).translationY(0f).setDuration(150).start()
                    
                    val intent = Intent(this@MainActivity, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_JOYSTICK_MOVE
                        putExtra(MockLocationService.EXTRA_JOYSTICK_DX, 0f)
                        putExtra(MockLocationService.EXTRA_JOYSTICK_DY, 0f)
                    }
                    startService(intent)
                    
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun updateRouteLine() {
        val map = mapLibre ?: return
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return
        val pointSource = style.getSourceAs<GeoJsonSource>(POINTS_SOURCE_ID) ?: return

        val points = routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }

        if (routePoints.size < 2) {
            source.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.setGeoJson(LineString.fromLngLats(points))
        }

        val features = points.map { point -> org.maplibre.geojson.Feature.fromGeometry(point) }
        pointSource.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(features))
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(loadedMapStyle: Style) {
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val locationComponent = mapLibre?.locationComponent
            val locationComponentActivationOptions =
                org.maplibre.android.location.LocationComponentActivationOptions.builder(this, loadedMapStyle)
                    .build()
            locationComponent?.activateLocationComponent(locationComponentActivationOptions)
            locationComponent?.isLocationComponentEnabled = true
            locationComponent?.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
        }
    }

    private fun setupMapLayers(style: Style) {
        // 登錄圖示
        getBitmapFromVectorDrawable(this, R.drawable.ic_route_pin)?.let { bitmap ->
            style.addImage("route-pin-icon", bitmap)
        }
        getBitmapFromVectorDrawable(this, R.drawable.ic_route_arrow)?.let { bitmap ->
            style.addImage("route-arrow-icon", bitmap)
        }

        if (style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
        }
        if (style.getSourceAs<GeoJsonSource>(POINTS_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(POINTS_SOURCE_ID))
        }

        if (style.getLayer(ROUTE_LAYER_ID) == null) {
            val lineLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(MAP_ROUTE_COLOR),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND)
            )
            style.addLayer(lineLayer)
        }

        // 加入沿著路徑方向的路徑箭頭 (Arrow)
        if (style.getLayer(ROUTE_ARROW_LAYER_ID) == null) {
            val arrowLayer = org.maplibre.android.style.layers.SymbolLayer(
                ROUTE_ARROW_LAYER_ID,
                ROUTE_SOURCE_ID
            ).withProperties(
                PropertyFactory.symbolPlacement(org.maplibre.android.style.layers.Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.iconImage("route-arrow-icon"),
                PropertyFactory.iconSize(0.8f),
                PropertyFactory.symbolSpacing(50f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
            style.addLayerAbove(arrowLayer, ROUTE_LAYER_ID)
        }

        // 以圖標 (Pin) 替代原本的 CircleLayer 作為點擊節點
        if (style.getLayer(POINTS_LAYER_ID) == null) {
            val pointLayer = org.maplibre.android.style.layers.SymbolLayer(
                POINTS_LAYER_ID,
                POINTS_SOURCE_ID
            ).withProperties(
                PropertyFactory.iconImage("route-pin-icon"),
                PropertyFactory.iconSize(0.6f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOffset(arrayOf(0f, -15f))
            )
            style.addLayerAbove(pointLayer, ROUTE_ARROW_LAYER_ID)
        }

        // --- 加入單點選取的 Pin 點圖層 ---
        getBitmapFromVectorDrawable(this, R.drawable.ic_location)?.let { bitmap ->
            style.addImage(ICON_ID_SELECTED, bitmap)
        }
        if (style.getSourceAs<GeoJsonSource>(SOURCE_ID_SELECTED) == null) {
            style.addSource(GeoJsonSource(SOURCE_ID_SELECTED))
        }
        if (style.getLayer(LAYER_ID_SELECTED) == null) {
            val selectedLayer = SymbolLayer(LAYER_ID_SELECTED, SOURCE_ID_SELECTED).withProperties(
                PropertyFactory.iconImage(ICON_ID_SELECTED),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOffset(arrayOf(0f, -18f)),
                PropertyFactory.iconColor(android.graphics.Color.RED)
            )
            style.addLayer(selectedLayer)
        }
    }

    private fun updateSelectedPointSource(latLng: org.maplibre.android.geometry.LatLng?) {
        val map = mapLibre ?: return
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID_SELECTED) ?: return
        if (latLng == null) {
            hasSelectedPin = false
            source.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(arrayOf()))
        } else {
            hasSelectedPin = true
            lastTappedPoint = RoutePoint(latLng.latitude, latLng.longitude)
            source.setGeoJson(org.maplibre.geojson.Feature.fromGeometry(Point.fromLngLat(latLng.longitude, latLng.latitude)))
        }
    }

    private fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }






    private fun buildCsv(events: List<GpsEventItem>): String {
        val sb = StringBuilder()
        sb.append("timestamp,lat,lng,accuracy,speed_mps\n")
        events.forEach { event ->
            sb.append(event.timestamp).append(",")
                .append(event.lat).append(",")
                .append(event.lng).append(",")
                .append(event.accuracy).append(",")
                .append(String.format("%.2f", event.speedMps))
                .append("\n")
        }
        return sb.toString()
    }

    private fun showTeleportDialog(initialLatLng: org.maplibre.android.geometry.LatLng? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_teleport, null)
        val editCoords = view.findViewById<android.widget.EditText>(R.id.editCoords)
        
        var defaultLat = initialLatLng?.latitude
        var defaultLng = initialLatLng?.longitude
        
        if (defaultLat == null || defaultLng == null) {
            defaultLat = currentMockLat
            defaultLng = currentMockLng
            
            if (defaultLat == 0.0) {
                val mapLoc = mapLibre?.locationComponent?.lastKnownLocation
                if (mapLoc != null) {
                    defaultLat = mapLoc.latitude
                    defaultLng = mapLoc.longitude
                }
            }
            
            if (defaultLat == 0.0) {
                try {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    val lastKnown = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                 ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    if (lastKnown != null && !isInvalidLocation(lastKnown.latitude, lastKnown.longitude)) {
                        defaultLat = lastKnown.latitude
                        defaultLng = lastKnown.longitude
                    }
                } catch (_: SecurityException) {}
            }
        }
        
        if (defaultLat != 0.0) {
            editCoords.setText("%.6f, %.6f".format(defaultLat, defaultLng))
        }

        val textDistanceStatus = view.findViewById<android.widget.TextView>(R.id.textDistanceStatus)
        val btnFormat = view.findViewById<android.widget.Button>(R.id.btnFormat)
        val btnPaste = view.findViewById<android.widget.ImageButton>(R.id.btnPaste)
        val checkWalkMode = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkWalkMode)
        val btnCancel = view.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnTeleportAction = view.findViewById<android.widget.Button>(R.id.btnTeleportAction)
        val btnTeleportAddFavorite = view.findViewById<android.widget.ImageButton>(R.id.btnTeleportAddFavorite)

        btnTeleportAddFavorite.setOnClickListener {
            val raw = editCoords.text.toString()
            val parts = raw.split(",")
            if (parts.size >= 2) {
                val targetLat = parts[0].trim().toDoubleOrNull()
                val targetLng = parts[1].trim().toDoubleOrNull()
                if (targetLat != null && targetLng != null) {
                    lastTappedPoint = RoutePoint(targetLat, targetLng)
                    addFavorite()
                    return@setOnClickListener
                }
            }
            Toast.makeText(this@MainActivity, R.string.error_invalid_coords, Toast.LENGTH_SHORT).show()
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                val raw = editCoords.text.toString()
                val parts = raw.split(",")
                if (parts.size >= 2) {
                    val targetLat = parts[0].trim().toDoubleOrNull()
                    val targetLng = parts[1].trim().toDoubleOrNull()
                    if (targetLat != null && targetLng != null) {
                        // 1. 優先使用模擬位置
                        var startLat = currentMockLat
                        var startLng = currentMockLng
                        
                        // 2. 如果沒在模擬，使用地圖顯示的當前位置 (Map Location Component)
                        if (startLat == 0.0) {
                            val mapLoc = mapLibre?.locationComponent?.lastKnownLocation
                            if (mapLoc != null) {
                                startLat = mapLoc.latitude
                                startLng = mapLoc.longitude
                            }
                        }
                        
                        // 3. 如果還是沒有，嘗試獲取真實位置 (保底)
                        if (startLat == 0.0) {
                            try {
                                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                                val lastKnown = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                             ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                if (lastKnown != null) {
                                    startLat = lastKnown.latitude
                                    startLng = lastKnown.longitude
                                }
                            } catch (_: SecurityException) {}
                        }
                        
                        if (startLat != 0.0) {
                            val dist = calculateDistance(startLat, startLng, targetLat, targetLng)
                            val cooldown = calculateCooldown(dist)
                            
                            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                            val activeMode = prefs.getString("pref_active_speed_mode", "walk") ?: "walk"
                            val speedKmh = when (activeMode) {
                                "jog" -> prefs.getString("pref_speed_jog", "14.4")?.toDoubleOrNull() ?: 14.4
                                "drive" -> prefs.getString("pref_speed_drive", "50.0")?.toDoubleOrNull() ?: 50.0
                                else -> prefs.getString("pref_speed_walk", "9.0")?.toDoubleOrNull() ?: 9.0
                            }
                            val speedMps = speedKmh / 3.6
                            val etaSeconds = if (speedMps > 0) (dist / speedMps).toLong() else 0L
                            val etaText = formatDuration(etaSeconds * 1000)
                            
                            textDistanceStatus.text = "Distance: %s - Cooldown: %ds - ETA: %s".format(formatDistance(dist), cooldown, etaText)
                        } else {
                            textDistanceStatus.text = "Distance: Unknown - Click map first"
                        }
                    }
                }
                updateHandler.postDelayed(this, 1000)
            }
        }
        
        dialog.setOnShowListener { updateHandler.post(updateRunnable) }
        dialog.setOnDismissListener { updateHandler.removeCallbacks(updateRunnable) }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val text = item?.text?.toString() ?: ""
            editCoords.setText(text)
        }

        btnFormat.setOnClickListener {
            val raw = editCoords.text.toString()
            val clean = raw.replace(Regex("[^0-9.,-]"), "")
            editCoords.setText(clean)
        }

        btnTeleportAction.setOnClickListener {
            val raw = editCoords.text.toString()
            val parts = raw.split(",")
            if (parts.size >= 2) {
                val lat = parts[0].trim().toDoubleOrNull()
                val lng = parts[1].trim().toDoubleOrNull()
                if (lat != null && lng != null) {
                    var startLat = 0.0
                    var startLng = 0.0
                    
                    // 優先使用地圖引擎目前顯示的位置作為起點 (解決 Walk mode 飄移問題)
                    val mapLoc = mapLibre?.locationComponent?.lastKnownLocation
                    if (mapLoc != null && mapLoc.latitude != 0.0 && mapLoc.longitude != 0.0) {
                        startLat = mapLoc.latitude
                        startLng = mapLoc.longitude
                    }
                    
                    if (startLat == 0.0) {
                        // 退回使用 LocationHelper (會抓硬體 GPS 或系統紀錄，最後才 fallback 到 currentMockLat)
                        val (bestLat, bestLng) = LocationHelper.getBestAvailableLocationSync(this@MainActivity, currentMockLat, currentMockLng)
                        startLat = bestLat
                        startLng = bestLng
                    }

                    if (startLat != 0.0) {
                        routePoints.clear()
                        routePoints.add(RoutePoint(startLat, startLng))
                        routePoints.add(RoutePoint(lat, lng))
                        updateRouteLine()
                        updateRouteStats()
                    }

                    val intent = Intent(this@MainActivity, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_TELEPORT
                        putExtra(MockLocationService.EXTRA_LAT, lat)
                        putExtra(MockLocationService.EXTRA_LNG, lng)
                        putExtra(MockLocationService.EXTRA_WALK_MODE, checkWalkMode.isChecked)
                        putExtra("extra_start_lat", startLat)
                        putExtra("extra_start_lng", startLng)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }

                    mapLibre?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            org.maplibre.android.geometry.LatLng(lat, lng),
                            15.5
                        )
                    )
                }
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            mapLibre?.style?.let { style -> enableLocationComponent(style) }
            centerToCurrentLocation()
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun centerToCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        LocationHelper.fetchCurrentLocationAsync(this, currentMockLat, currentMockLng,
            onSuccess = { lat, lng ->
                mapLibre?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(lat, lng),
                        16.0
                    ),
                    1000
                )
            },
            onFailure = {
                Toast.makeText(this, "無法獲取定位，請確認 GPS 已開啟且位於收訊良好處", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun saveRoute() {
        if (routePoints.size < 2) {
            Toast.makeText(this, R.string.error_route_points, Toast.LENGTH_SHORT).show()
            return
        }

        val input = android.widget.EditText(this)
        input.hint = getString(R.string.route_name_hint)

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.button_save_route)
            .setView(input)
            .setPositiveButton(R.string.button_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.error_route_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val entity = RouteEntity(
                    name = name,
                    pointsJson = RouteJson.toJson(routePoints),
                    createdAt = System.currentTimeMillis()
                )

                val db = AppDatabase.getInstance(this)
                lifecycleScope.launch {
                    db.routeDao().insert(entity)
                    binding.layoutRouteEditor.visibility = android.view.View.GONE
                    Toast.makeText(this@MainActivity, "Route saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun renameSelectedRoute() {
        val id = selectedRouteId ?: run {
            Toast.makeText(this, R.string.error_route_select, Toast.LENGTH_SHORT).show()
            return
        }
    }

    private fun addFavorite() {
        val point = lastTappedPoint ?: run {
            Toast.makeText(this, R.string.error_favorite_point, Toast.LENGTH_SHORT).show()
            return
        }

        val input = android.widget.EditText(this)
        input.hint = getString(R.string.hint_favorite_name)

        // 背景進行 Geocoding 查詢地址
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val geocoder = android.location.Geocoder(this@MainActivity, java.util.Locale.getDefault())
                val addresses = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val result = kotlinx.coroutines.CompletableDeferred<List<android.location.Address>?>()
                    geocoder.getFromLocation(point.latitude, point.longitude, 1) { addrList ->
                        result.complete(addrList)
                    }
                    result.await()
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(point.latitude, point.longitude, 1)
                }
                
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    // 組合出友善的名稱，例如：城市名、區域或是地址
                    val friendlyName = address.locality ?: address.subAdminArea ?: address.adminArea ?: address.getAddressLine(0)
                    if (friendlyName != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (input.text.isEmpty()) { // 在使用者還沒輸入的情況下才覆寫
                                input.setText(friendlyName)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 地理編碼失敗不中斷流程
                android.util.Log.e("MockApp", "Geocoding failed", e)
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.button_add_favorite)
            .setView(input)
            .setPositiveButton(R.string.button_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.error_favorite_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val entity = FavoriteEntity(
                    name = name,
                    lat = point.latitude,
                    lng = point.longitude,
                    note = null,
                    createdAt = System.currentTimeMillis()
                )
                val db = AppDatabase.getInstance(this)
                lifecycleScope.launch {
                    db.favoriteDao().insert(entity)
                    Toast.makeText(this@MainActivity, "Favorite Added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun showFavoriteEditDialog(item: FavoriteItem) {
        val view = layoutInflater.inflate(R.layout.dialog_favorite_edit, null)
        val nameField = view.findViewById<android.widget.EditText>(R.id.editFavoriteNameDialog)
        val noteField = view.findViewById<android.widget.EditText>(R.id.editFavoriteNoteDialog)
        val addToRouteButton = view.findViewById<android.widget.Button>(R.id.buttonAddToRouteDialog)
        nameField.setText(item.name)
        noteField.setText(item.note ?: "")
        addToRouteButton.setOnClickListener {
            addFavoriteToRoute(item)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_edit_favorite))
            .setView(view)
            .setPositiveButton(getString(R.string.button_save)) { _, _ ->
                val newName = nameField.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, R.string.error_favorite_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newNote = noteField.text.toString().trim().ifEmpty { null }
                val db = AppDatabase.getInstance(this)
                lifecycleScope.launch {
                    db.favoriteDao().update(item.id, newName, newNote)
                }
            }
            .setNeutralButton(getString(R.string.button_use_as_start)) { _, _ ->
                useFavoriteAsStart(item)
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
            .show()
    }

    private fun useFavoriteAsStart(item: FavoriteItem) {
        routePoints.clear()
        routePoints.add(RoutePoint(item.lat, item.lng))
        updateRouteLine()
        updateRouteStats()
        selectedRouteId = null
        mapLibre?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                org.maplibre.android.geometry.LatLng(item.lat, item.lng),
                15.5
            )
        )
    }

    private fun addFavoriteToRoute(item: FavoriteItem) {
        routePoints.add(RoutePoint(item.lat, item.lng))
        updateRouteLine()
        updateRouteStats()
        mapLibre?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                org.maplibre.android.geometry.LatLng(item.lat, item.lng),
                15.5
            )
        )
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
            updateRouteStats()
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
        updateRouteStats()
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

    private fun updateRouteStats() {
        if (routePoints.size < 2) {
            binding.textRouteStats.text = getString(R.string.route_stats_empty, routePoints.size)
            return
        }
        var totalDist = 0.0
        for (i in 0 until routePoints.size - 1) {
            totalDist += haversineMeters(
                routePoints[i].latitude, routePoints[i].longitude,
                routePoints[i + 1].latitude, routePoints[i + 1].longitude
            )
        }
        
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val defaultWalk = prefs.getString("pref_speed_walk", "9.0") ?: "9.0"
        val activeSpeed = prefs.getString("pref_active_speed", defaultWalk)?.toDoubleOrNull() ?: 9.0
        val speedMps = activeSpeed / 3.6
        
        val estSeconds = totalDist / speedMps
        val distStr = if (totalDist >= 1000) "%.2f km".format(totalDist / 1000.0) else "%.0f m".format(totalDist)
        val timeStr = formatDuration((estSeconds * 1000).toLong())
        binding.textRouteStats.text = getString(R.string.route_stats_format, routePoints.size, distStr, timeStr, activeSpeed)
    }

    private fun startRoutePlayback() {
        if (routePoints.size < 2) {
            Toast.makeText(this, R.string.error_route_points, Toast.LENGTH_SHORT).show()
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.title_route_start_mode)
            .setMessage(R.string.message_route_start_mode)
            .setPositiveButton(R.string.button_walk_to_start) { _, _ ->
                launchRoutePlayback(true)
            }
            .setNegativeButton(R.string.button_teleport_to_start) { _, _ ->
                launchRoutePlayback(false)
            }
            .setNeutralButton(R.string.button_cancel, null)
            .show()
    }

    private fun launchRoutePlayback(walkToStart: Boolean) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        
        val defaultWalk = prefs.getString("pref_speed_walk", "9.0") ?: "9.0"
        val activeSpeed = prefs.getString("pref_active_speed", defaultWalk)?.toDoubleOrNull() ?: 9.0
        val pauseShort = prefs.getString("pref_pause_short", "5")?.toDoubleOrNull() ?: 5.0
        
        val randomSpeed = prefs.getBoolean("pref_random_speed", true)
        val loopEnabled = prefs.getBoolean("pref_loop_enabled", false)
        val loopCountStr = prefs.getString("pref_loop_count", "0") ?: "0"
        val loopCount = loopCountStr.toIntOrNull() ?: 0
        
        val driftEnabled = prefs.getBoolean("pref_drift_enabled", false)
        val bounceEnabled = prefs.getBoolean("pref_bounce_enabled", false)
        val driftMeters = prefs.getString("pref_drift_meters", "5")?.toDoubleOrNull() ?: 5.0
        val bounceMeters = prefs.getString("pref_bounce_meters", "2")?.toDoubleOrNull() ?: 2.0

        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
            putExtra(MockLocationService.EXTRA_ROUTE_JSON, RouteJson.toJson(routePoints))
            putExtra(MockLocationService.EXTRA_WALK_TO_START, walkToStart)
            // Use map location if possible, next physical, finally mock.
            val mapLoc = mapLibre?.locationComponent?.lastKnownLocation
            val startLat = if (mapLoc != null && mapLoc.latitude != 0.0) mapLoc.latitude else currentMockLat
            val startLng = if (mapLoc != null && mapLoc.longitude != 0.0) mapLoc.longitude else currentMockLng
            putExtra("extra_start_lat", startLat)
            putExtra("extra_start_lng", startLng)
            
            putExtra(MockLocationService.EXTRA_SPEED_MIN_KMH, activeSpeed * 0.9)
            putExtra(MockLocationService.EXTRA_SPEED_MAX_KMH, activeSpeed * 1.1)
            putExtra(MockLocationService.EXTRA_PAUSE_MIN_SEC, pauseShort)
            putExtra(MockLocationService.EXTRA_PAUSE_MAX_SEC, pauseShort * 1.5)
            
            putExtra(MockLocationService.EXTRA_RANDOM_SPEED, randomSpeed)
            putExtra(MockLocationService.EXTRA_LOOP_ENABLED, loopEnabled)
            putExtra(MockLocationService.EXTRA_LOOP_COUNT, loopCount)
            putExtra(MockLocationService.EXTRA_DRIFT_ENABLED, driftEnabled)
            putExtra(MockLocationService.EXTRA_BOUNCE_ENABLED, bounceEnabled)
            putExtra(MockLocationService.EXTRA_DRIFT_METERS, driftMeters)
            putExtra(MockLocationService.EXTRA_BOUNCE_METERS, bounceMeters)
        }
        if (!needsLocationPermission()) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            Log.w("MockApp", "launchRoutePlayback: Missing location permission, skipping service start")
            Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
        }
        isRouteRunning = true
        binding.buttonStartRoute.text = getString(R.string.button_stop)
        binding.layoutPlaybackStats.visibility = View.VISIBLE
        binding.textStatus.setText(R.string.status_running)
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    private fun isInRange(value: Double, min: Double, max: Double): Boolean {
        return value >= min && value <= max
    }

    private fun pauseRoutePlayback() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_PAUSE_ROUTE
        }
        if (!needsLocationPermission()) {
            startService(intent)
        }
    }

    private fun stopRoutePlayback() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP_ROUTE
        }
        if (!needsLocationPermission()) {
            startService(intent)
        }
        // do not stop service to keep generating mock location at the last point
        binding.textStatus.setText(R.string.status_idle)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        ContextCompat.registerReceiver(
            this,
            mockStatusReceiver,
            IntentFilter(MockLocationService.ACTION_MOCK_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            mockProgressReceiver,
            IntentFilter(MockLocationService.ACTION_MOCK_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            mockRouteReceiver,
            IntentFilter(MockLocationService.ACTION_ROUTE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Sync state from MockLocationService
        val prefs = getSharedPreferences(MockLocationService.PREFS_NAME, Context.MODE_PRIVATE)
        val isRunning = prefs.getBoolean(MockLocationService.PREF_KEY_RUNNING, false)
        isRouteRunning = isRunning
        binding.buttonStartRoute.text = if (isRunning) getString(R.string.button_stop) else getString(R.string.button_start)
        if (isRunning) {
            binding.layoutPlaybackStats.visibility = View.VISIBLE
            binding.textStatus.setText(R.string.status_running)
        }

        // Restore walk menu icon
        val commonPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val savedMode = commonPrefs.getString("pref_active_speed_mode", "walk") ?: "walk"
        updateWalkMenuIcon(savedMode)
        
        if (!isLocationEnabled()) {
            binding.textStatus.setText(R.string.status_error)
            Toast.makeText(this, R.string.error_location_disabled, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        
        // Sync the current active mode's speed from SharedPreferences
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val activeMode = prefs.getString("pref_active_speed_mode", "walk") ?: "walk"
        val newSpeed = when (activeMode) {
            "jog" -> prefs.getString("pref_speed_jog", "14.4")?.toDoubleOrNull() ?: 14.4
            "drive" -> prefs.getString("pref_speed_drive", "50.0")?.toDoubleOrNull() ?: 50.0
            else -> prefs.getString("pref_speed_walk", "9.0")?.toDoubleOrNull() ?: 9.0
        }
        applySpeedPreset(newSpeed, activeMode, showToast = false)
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()

        // Caching camera position for zero-delay next startup
        mapLibre?.cameraPosition?.let { pos ->
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit()
                .putFloat("pref_last_lat", pos.target!!.latitude.toFloat())
                .putFloat("pref_last_lng", pos.target!!.longitude.toFloat())
                .putFloat("pref_last_zoom", pos.zoom.toFloat())
                .apply()
        }
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        try {
            unregisterReceiver(mockStatusReceiver)
            unregisterReceiver(mockProgressReceiver)
            unregisterReceiver(mockRouteReceiver)
        } catch (e: Exception) {
            android.util.Log.e("MockApp", "Error unregistering receivers", e)
        }
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
        if (requestCode == REQUEST_CODE_PERMISSIONS || requestCode == REQUEST_LOCATION_PERMISSION || requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Permissions granted, we might want to start something or just let user click again
                Log.i("MockApp", "Permissions granted")
            } else {
                Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun syncStatusFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isRunning = prefs.getBoolean(PREF_KEY_RUNNING, false)
        binding.textStatus.setText(if (isRunning) R.string.status_running else R.string.status_idle)
        
        // 如果正在執行，則恢復顯示統計條
        if (isRunning) {
            binding.layoutPlaybackStats.visibility = View.VISIBLE
            binding.buttonStartRoute.text = "Stop Route"
            isRouteRunning = true
            isRouteRunning = true
        } else {
            binding.layoutPlaybackStats.visibility = View.GONE
            binding.buttonStartRoute.text = "Start Route"
            isRouteRunning = false
        }
    }

    private val mockRouteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val json = intent.getStringExtra(MockLocationService.EXTRA_ROUTE_JSON) ?: return
                val points = RouteJson.fromJson(json)
                if (points.isNotEmpty()) {
                    routePoints.clear()
                    routePoints.addAll(points)
                    updateRouteLine()
                    updateRouteStats()
                }
            } catch (e: Exception) {
                android.util.Log.e("MockApp", "Error in mockRouteReceiver", e)
            }
        }
    }

    private fun loadRoute(entity: com.sofawander.app.data.RouteEntity) {
        val points = RouteJson.fromJson(entity.pointsJson)
        if (points.isNotEmpty()) {
            routePoints.clear()
            routePoints.addAll(points)
            selectedRouteId = entity.id
            updateRouteLine()
            updateRouteStats()
            binding.layoutRouteEditor.visibility = View.VISIBLE
            binding.buttonStartRoute.text = getString(R.string.button_start)
            Toast.makeText(this, "Loaded: ${entity.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeFavorites() {
        AppDatabase.getInstance(this).favoriteDao().getAllFavorites().asLiveData().observe(this) { favorites ->
            val items = favorites.map {
                com.sofawander.app.ui.FavoriteItem(
                    id = it.id,
                    name = it.name,
                    lat = it.lat,
                    lng = it.lng,
                    note = it.note
                )
            }
            favoriteAdapter.submitList(items)
        }
    }

    private fun observeHistory() {
        AppDatabase.getInstance(this).runHistoryDao().getAllHistoryFlow().asLiveData().observe(this) { history ->
            val items = history.map {
                com.sofawander.app.ui.RunHistoryItem(
                    id = it.id,
                    routeName = it.routeName,
                    pointCount = it.pointCount,
                    speedMode = it.speedMode,
                    loopEnabled = it.loopEnabled,
                    roundTripEnabled = it.roundTripEnabled,
                    startedAt = it.startedAt,
                    endedAt = it.endedAt,
                    status = it.status
                )
            }
            historyAdapter.submitList(items)
        }
    }

    private fun showRunDetails(item: RunHistoryItem) {
        // Implement logic to show run details or replay
        android.app.AlertDialog.Builder(this)
            .setTitle(item.routeName ?: "History Run")
            .setMessage("Points: ${item.pointCount}\nStatus: ${item.status}")
            .setPositiveButton("Replay") { _, _ ->
                loadAndReplayHistory(item.id)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun applySpeedPreset(kmh: Double, mode: String, showToast: Boolean = true) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putString("pref_active_speed", kmh.toString())
            .putString("pref_active_speed_mode", mode)
            .apply()
        updateWalkMenuIcon(mode)
        updateRouteStats()
        
        // Always update the service with new speed settings if it's potentially running
        // BUT only if we have permissions on Android 14+
        if (!needsLocationPermission()) {
            val intent = Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_UPDATE_SPEED
                putExtra(MockLocationService.EXTRA_SPEED_MIN_KMH, kmh * 0.9)
                putExtra(MockLocationService.EXTRA_SPEED_MAX_KMH, kmh * 1.1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            Log.w("MockApp", "applySpeedPreset: Missing location permission, skipping service update")
        }
        
        if (showToast) {
            Toast.makeText(this, "Speed set to $kmh km/h", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateWalkMenuIcon(mode: String) {
        val iconResId = when (mode) {
            "walk" -> R.drawable.ic_directions_walk
            "jog" -> R.drawable.ic_directions_run
            "drive" -> R.drawable.ic_drive
            else -> R.drawable.ic_directions_walk
        }
        binding.btnWalkMenu.setImageResource(iconResId)
    }

    private fun ensurePermissionsAndStart() {
        val needsNotification = needsNotificationPermission()
        val needsLocation = needsLocationPermission()
        
        val missing = mutableListOf<String>()
        if (needsNotification) missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (needsLocation) {
            missing.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            startRoutePlayback()
        }
    }

    private fun needsLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    }

    private fun showImportSummary(count: Int) {
        Toast.makeText(this, "Imported $count points", Toast.LENGTH_SHORT).show()
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
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val MAP_STYLE_URL = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"
        private const val SOURCE_ID_SELECTED = "selected-point-source"
        private const val LAYER_ID_SELECTED = "selected-point-layer"
        private const val ICON_ID_SELECTED = "selected-point-icon"
        
        private const val ROUTE_SOURCE_ID = "route-source"
        private const val ROUTE_LAYER_ID = "route-layer"
        private const val ROUTE_ARROW_LAYER_ID = "route-arrow-layer"
        private const val POINTS_SOURCE_ID = "points-source"
        private const val POINTS_LAYER_ID = "points-layer"

        private const val MAP_ROUTE_COLOR = "#21f380"
        private const val MAP_POINT_COLOR = "#FF5722"
        private const val MAP_POINT_STROKE = "#FFFFFF"
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun calculateCooldown(meters: Double): Int {
        val km = meters / 1000.0
        return when {
            km < 0.25 -> 0
            km < 0.5 -> 30
            km < 1.0 -> 60
            km < 5.0 -> 120
            km < 10.0 -> 420
            km < 25.0 -> 660
            km < 50.0 -> 1200
            km < 100.0 -> 2100
            km < 250.0 -> 2700
            km < 500.0 -> 3600
            km < 1000.0 -> 4800
            else -> 7200
        }
    }

    private fun performSearch() {
        val query = binding.editSearch.text.toString().trim()
        if (query.isEmpty()) return

        hideKeyboard()

        // 1. 嘗試解析是否為座標 "lat, lng"
        val parts = query.split(",")
        if (parts.size >= 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null) {
                val point = org.maplibre.android.geometry.LatLng(lat, lng)
                mapLibre?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(point, 15.0))
                updateSelectedPointSource(point)
                showTeleportDialog(point)
                return
            }
        }

        // 2. 使用 Geocoder 搜尋地址
        if (!Geocoder.isPresent()) {
            Toast.makeText(this, R.string.error_geocoder_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val geocoder = Geocoder(this, Locale.getDefault())
        try {
            // 注意：API 33+ 有新的 getFromLocationName，這裡用舊的相容版或簡易版
            val addresses = geocoder.getFromLocationName(query, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val point = org.maplibre.android.geometry.LatLng(addr.latitude, addr.longitude)
                mapLibre?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(point, 15.0))
                updateSelectedPointSource(point)
                showTeleportDialog(point)
            } else {
                Toast.makeText(this, R.string.error_location_not_found, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("MockApp", "Search error", e)
            Toast.makeText(this, getString(R.string.error_search_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }



    private fun loadAndReplayHistory(historyId: Long) {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@MainActivity)
            val run = db.runHistoryDao().getById(historyId) ?: return@launch
            val points = RouteJson.fromJson(run.routePointsJson ?: "")
            if (points.isNotEmpty()) {
                routePoints.clear()
                routePoints.addAll(points)
                updateRouteLine()
                updateRouteStats()
                Toast.makeText(this@MainActivity, "Replaying history...", Toast.LENGTH_SHORT).show()
                startRoutePlayback()
            } else {
                Toast.makeText(this@MainActivity, "No points found in this history", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}
