package com.sofawander.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sofawander.app.R
import com.sofawander.app.data.AppDatabase
import com.sofawander.app.data.RouteJson
import com.sofawander.app.data.RouteFileIO
import com.sofawander.app.databinding.ActivityRouteListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouteListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRouteListBinding
    private lateinit var adapter: RouteAdapter

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importRoute(uri)
        }
    }

    private val createGpxLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) {
            exportRoute(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRouteListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupActions()
        observeRoutes()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = RouteAdapter { item ->
            showRouteOptionsDialog(item)
        }
        binding.recyclerRoutes.layoutManager = LinearLayoutManager(this)
        binding.recyclerRoutes.adapter = adapter
    }

    private fun setupActions() {
        binding.buttonImportGpx.setOnClickListener {
            openDocumentLauncher.launch(
                arrayOf(
                    "application/gpx+xml",
                    "application/vnd.google-earth.kml+xml",
                    "application/octet-stream",
                    "text/xml",
                    "*/*"
                )
            )
        }
    }

    private fun observeRoutes() {
        val routeDao = AppDatabase.getInstance(this).routeDao()
        routeDao.getAllRoutes().asLiveData().observe(this) { routes ->
            val items = routes.map {
                RouteItem(
                    id = it.id,
                    name = it.name,
                    points = RouteJson.fromJson(it.pointsJson),
                    distanceMeters = it.distanceMeters,
                    durationMs = it.durationMs,
                    locationSummary = it.locationSummary,
                    createdAt = it.createdAt
                )
            }
            adapter.submitList(items)
            binding.textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showRouteOptionsDialog(item: RouteItem) {
        val options = arrayOf(
            getString(R.string.button_load_route_to_map),
            getString(R.string.button_export_route),
            getString(R.string.button_delete_route)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Load to Map
                        val resultIntent = Intent().apply {
                            putExtra("EXTRA_ROUTE_ID", item.id)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    }
                    1 -> { // Export
                        createGpxLauncher.launch("route_${item.name}.gpx")
                    }
                    2 -> { // Delete
                        deleteRoute(item)
                    }
                }
            }
            .show()
    }

    private fun deleteRoute(item: RouteItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_delete_route)
            .setMessage(getString(R.string.msg_confirm_delete_route, item.name))
            .setPositiveButton(R.string.button_delete_route) { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@RouteListActivity).routeDao().deleteById(item.id)
                    Toast.makeText(this@RouteListActivity, R.string.msg_route_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun importRoute(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val input = contentResolver.openInputStream(uri) ?: throw Exception("Cannot open stream")
                var name = "Imported Route"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            name = cursor.getString(nameIndex).substringBeforeLast('.')
                        }
                    }
                }
                input.use { stream ->
                    val isKml = uri.toString().endsWith(".kml", true) ||
                            contentResolver.getType(uri)?.contains("kml") == true
                    val points = if (isKml) {
                        RouteFileIO.parseKml(stream)
                    } else {
                        RouteFileIO.parseGpx(stream)
                    }
                    if (points.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@RouteListActivity, R.string.error_invalid_route, Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val json = RouteJson.toJson(points)
                    val routeDao = AppDatabase.getInstance(this@RouteListActivity).routeDao()
                    routeDao.insert(com.sofawander.app.data.RouteEntity(
                        name = name, 
                        pointsJson = json,
                        createdAt = System.currentTimeMillis()
                    ))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@RouteListActivity, R.string.msg_route_imported, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RouteListActivity, R.string.error_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportRoute(uri: Uri) {
        val selectedId = adapter.getSelectedId() ?: return
        val item = adapter.currentList.find { it.id == selectedId } ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val output = contentResolver.openOutputStream(uri) ?: throw Exception("Cannot open stream")
                output.use { stream ->
                    RouteFileIO.writeGpx(stream, item.name, item.points)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RouteListActivity, R.string.msg_route_exported, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RouteListActivity, R.string.error_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
