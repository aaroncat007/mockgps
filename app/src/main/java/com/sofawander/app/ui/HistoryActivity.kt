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
            // 點擊後將資料帶回 MainActivity 並開始播放
            val intent = Intent().apply {
                putExtra("HISTORY_ID", item.id)
            }
            setResult(RESULT_OK, intent)
            finish()
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
}
