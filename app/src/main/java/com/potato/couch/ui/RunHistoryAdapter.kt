package com.potato.couch.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.potato.couch.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RunHistoryAdapter(
    private val onClick: (RunHistoryItem) -> Unit
) : ListAdapter<RunHistoryItem, RunHistoryAdapter.RunViewHolder>(DiffCallback()) {

    private val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RunViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return RunViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: RunViewHolder, position: Int) {
        holder.bind(getItem(position), formatter)
    }

    class RunViewHolder(
        itemView: View,
        private val onClick: (RunHistoryItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.textHistoryTitle)
        private val metaView: TextView = itemView.findViewById(R.id.textHistoryMeta)
        private val statusView: TextView = itemView.findViewById(R.id.textHistoryStatus)

        fun bind(item: RunHistoryItem, formatter: SimpleDateFormat) {
            val title = item.routeName ?: "Route (${item.pointCount} pts)"
            val time = formatter.format(Date(item.startedAt))
            titleView.text = "$title · $time"
            val loop = if (item.loopEnabled) "Loop" else ""
            val roundtrip = if (item.roundTripEnabled) "Round" else ""
            metaView.text = "${item.status} $loop $roundtrip"
            val statusColor = when (item.status.uppercase(Locale.US)) {
                "COMPLETED" -> R.color.green_600
                "RUNNING" -> R.color.amber_600
                "STOPPED" -> R.color.red_600
                else -> R.color.slate_700
            }
            metaView.setTextColor(itemView.context.getColor(statusColor))
            statusView.text = item.status
            statusView.setTextColor(itemView.context.getColor(statusColor))
            itemView.setOnClickListener { onClick(item) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RunHistoryItem>() {
        override fun areItemsTheSame(oldItem: RunHistoryItem, newItem: RunHistoryItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: RunHistoryItem, newItem: RunHistoryItem): Boolean {
            return oldItem == newItem
        }
    }
}
