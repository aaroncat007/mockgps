package com.sofawander.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sofawander.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RunHistoryAdapter(
    private val onClick: (RunHistoryItem) -> Unit
) : ListAdapter<RunHistoryItem, RunHistoryAdapter.RunViewHolder>(DiffCallback()) {

    private val formatter = SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RunViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_card, parent, false)
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
        private val indicator: View = itemView.findViewById(R.id.viewStatusIndicator)
        private val btnReplay: ImageView = itemView.findViewById(R.id.btnReplay)

        fun bind(item: RunHistoryItem, formatter: SimpleDateFormat) {
            val routeTitle = item.routeName ?: "未命名路線 (${item.pointCount} 點)"
            titleView.text = routeTitle
            
            val time = formatter.format(Date(item.startedAt))
            val loopStr = if (item.loopEnabled) " · 循環" else ""
            val roundStr = if (item.roundTripEnabled) " · 來回" else ""
            metaView.text = "$time$loopStr$roundStr · ${item.status}"

            val color = when (item.status.uppercase(Locale.US)) {
                "COMPLETED" -> R.color.emerald_500
                "RUNNING" -> R.color.amber_600
                "STOPPED" -> R.color.red_600
                else -> R.color.slate_400
            }
            indicator.setBackgroundColor(itemView.context.getColor(color))
            
            itemView.setOnClickListener { onClick(item) }
            btnReplay.setOnClickListener { onClick(item) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RunHistoryItem>() {
        override fun areItemsTheSame(oldItem: RunHistoryItem, newItem: RunHistoryItem): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: RunHistoryItem, newItem: RunHistoryItem): Boolean = oldItem == newItem
    }
}
