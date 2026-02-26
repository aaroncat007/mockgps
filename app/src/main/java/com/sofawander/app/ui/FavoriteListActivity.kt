package com.sofawander.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sofawander.app.R
import com.sofawander.app.data.AppDatabase
import com.sofawander.app.databinding.ActivityFavoriteListBinding
import kotlinx.coroutines.launch

class FavoriteListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavoriteListBinding
    private lateinit var adapter: FavoriteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoriteListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        observeFavorites()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = FavoriteAdapter(
            onClick = { item ->
                showOptionsDialog(item)
            },
            onDelete = { item ->
                deleteFavorite(item)
            }
        )
        binding.recyclerFavorites.layoutManager = LinearLayoutManager(this)
        binding.recyclerFavorites.adapter = adapter
    }

    private fun observeFavorites() {
        val favoriteDao = AppDatabase.getInstance(this).favoriteDao()
        favoriteDao.getAllFavorites().asLiveData().observe(this) { favorites ->
            val items = favorites.map {
                FavoriteItem(
                    id = it.id,
                    name = it.name,
                    lat = it.lat,
                    lng = it.lng,
                    locationDescription = it.locationDescription,
                    note = it.note
                )
            }
            adapter.submitList(items)
            binding.textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showOptionsDialog(item: FavoriteItem) {
        val options = arrayOf(
            getString(R.string.title_show_on_map),
            getString(R.string.title_teleport)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                val resultIntent = Intent().apply {
                    putExtra("EXTRA_FAV_LAT", item.lat)
                    putExtra("EXTRA_FAV_LNG", item.lng)
                    putExtra("EXTRA_FAV_ID", item.id)
                    putExtra("EXTRA_ACTION", if (which == 0) "SHOW_MAP" else "TELEPORT")
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
            .show()
    }

    private fun deleteFavorite(item: FavoriteItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_delete_favorite)
            .setMessage(getString(R.string.msg_confirm_delete_favorite, item.name))
            .setPositiveButton(R.string.button_delete_favorite) { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@FavoriteListActivity).favoriteDao().deleteById(item.id)
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }
}
