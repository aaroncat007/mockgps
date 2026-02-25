package com.sofawander.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sofawander.app.databinding.ActivityHistoryBinding
import com.sofawander.app.data.AppDatabase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Intent

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: RunHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        observeHistory()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = RunHistoryAdapter { item ->
            showOptionsDialog(item)
        }
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter
    }

    private fun observeHistory() {
        lifecycleScope.launch {
            AppDatabase.getInstance(this@HistoryActivity).runHistoryDao()
                .getAllHistoryFlow().collectLatest { list ->
                    val items = list.map {
                        RunHistoryItem(
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
                    if (items.isEmpty()) {
                        binding.textEmpty.visibility = View.VISIBLE
                        binding.recyclerHistory.visibility = View.GONE
                    } else {
                        binding.textEmpty.visibility = View.GONE
                        binding.recyclerHistory.visibility = View.VISIBLE
                        adapter.submitList(items)
                    }
                }
        }
    }

    private fun showOptionsDialog(item: RunHistoryItem) {
        val options = arrayOf(
            getString(com.sofawander.app.R.string.action_history_replay),
            getString(com.sofawander.app.R.string.action_history_load),
            getString(com.sofawander.app.R.string.action_history_rename),
            getString(com.sofawander.app.R.string.action_history_delete)
        )

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(item.routeName ?: getString(com.sofawander.app.R.string.title_history_options))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> returnResult("REPLAY", item.id)
                    1 -> returnResult("LOAD", item.id)
                    2 -> showRenameDialog(item)
                    3 -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun returnResult(action: String, id: Long) {
        val intent = Intent().apply {
            putExtra("HISTORY_ACTION", action)
            putExtra("HISTORY_ID", id)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun showRenameDialog(item: RunHistoryItem) {
        val input = android.widget.EditText(this)
        input.setText(item.routeName)
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(com.sofawander.app.R.string.title_rename_history)
            .setView(input)
            .setPositiveButton(com.sofawander.app.R.string.button_save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        AppDatabase.getInstance(this@HistoryActivity).runHistoryDao()
                            .rename(item.id, newName)
                    }
                }
            }
            .setNegativeButton(com.sofawander.app.R.string.button_cancel, null)
            .show()
    }

    private fun confirmDelete(item: RunHistoryItem) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(com.sofawander.app.R.string.action_history_delete)
            .setMessage(getString(com.sofawander.app.R.string.msg_confirm_delete_favorite, item.routeName ?: "Route"))
            .setPositiveButton(com.sofawander.app.R.string.button_delete) { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@HistoryActivity).runHistoryDao()
                        .deleteById(item.id)
                    android.widget.Toast.makeText(this@HistoryActivity, com.sofawander.app.R.string.msg_history_deleted, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(com.sofawander.app.R.string.button_cancel, null)
            .show()
    }
}
