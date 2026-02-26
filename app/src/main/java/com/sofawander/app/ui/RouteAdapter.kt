package com.sofawander.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sofawander.app.R

class RouteAdapter(
    private val onClick: (RouteItem) -> Unit
) : ListAdapter<RouteItem, RouteAdapter.RouteViewHolder>(DiffCallback()) {

    private var selectedId: Long? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route, parent, false)
        return RouteViewHolder(view, onClick) { id -> setSelected(id) }
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        holder.bind(getItem(position), selectedId)
    }

    fun getSelectedId(): Long? = selectedId

    private fun setSelected(id: Long) {
        val previousId = selectedId
        selectedId = id
        if (previousId == id) return
        if (previousId != null) {
            val previousIndex = currentList.indexOfFirst { it.id == previousId }
            if (previousIndex >= 0) notifyItemChanged(previousIndex)
        }
        val newIndex = currentList.indexOfFirst { it.id == id }
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    class RouteViewHolder(
        itemView: View,
        private val onClick: (RouteItem) -> Unit,
        private val onSelect: (Long) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val nameView: TextView = itemView.findViewById(R.id.textRouteName)
        private val summaryView: TextView = itemView.findViewById(R.id.textRouteSummary)
        private val distanceView: TextView = itemView.findViewById(R.id.textRouteDistance)
        private val durationView: TextView = itemView.findViewById(R.id.textRouteDuration)

        fun bind(item: RouteItem, selectedId: Long?) {
            nameView.text = item.name
            
            if (!item.locationSummary.isNullOrEmpty()) {
                summaryView.text = item.locationSummary
                summaryView.visibility = View.VISIBLE
            } else {
                summaryView.visibility = View.GONE
            }

            distanceView.text = formatDistance(item.distanceMeters)
            durationView.text = formatDuration(item.durationMs)

            itemView.isSelected = item.id == selectedId
            itemView.setOnClickListener {
                onSelect(item.id)
                onClick(item)
            }
        }

        private fun formatDistance(meters: Double): String {
            return if (meters >= 1000) "%.2f km".format(meters / 1000.0) else "%.0f m".format(meters)
        }

        private fun formatDuration(millis: Long): String {
            val seconds = (millis / 1000) % 60
            val minutes = (millis / (1000 * 60)) % 60
            val hours = (millis / (1000 * 60 * 60))
            return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
            else "%02d:%02d".format(minutes, seconds)
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RouteItem>() {
        override fun areItemsTheSame(oldItem: RouteItem, newItem: RouteItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: RouteItem, newItem: RouteItem): Boolean {
            return oldItem == newItem
        }
    }
}
