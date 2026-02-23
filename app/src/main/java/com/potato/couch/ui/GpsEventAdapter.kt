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

class GpsEventAdapter : ListAdapter<GpsEventItem, GpsEventAdapter.EventViewHolder>(DiffCallback()) {

    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position), formatter)
    }

    class EventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.textEventTitle)
        private val metaView: TextView = itemView.findViewById(R.id.textEventMeta)

        fun bind(item: GpsEventItem, formatter: SimpleDateFormat) {
            val time = formatter.format(Date(item.timestamp))
            titleView.text = "$time · ${"%.5f".format(item.lat)}, ${"%.5f".format(item.lng)}"
            metaView.text = "acc ${item.accuracy}m · ${"%.2f".format(item.speedMps)} m/s"
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<GpsEventItem>() {
        override fun areItemsTheSame(oldItem: GpsEventItem, newItem: GpsEventItem): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.lat == newItem.lat
        }

        override fun areContentsTheSame(oldItem: GpsEventItem, newItem: GpsEventItem): Boolean {
            return oldItem == newItem
        }
    }
}
