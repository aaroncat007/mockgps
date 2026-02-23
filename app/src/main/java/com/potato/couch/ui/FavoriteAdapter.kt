package com.potato.couch.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.potato.couch.R

class FavoriteAdapter(
    private val onClick: (FavoriteItem) -> Unit
) : ListAdapter<FavoriteItem, FavoriteAdapter.FavoriteViewHolder>(DiffCallback()) {

    private var selectedId: Long? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite, parent, false)
        return FavoriteViewHolder(view, onClick) { id -> setSelected(id) }
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
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

    class FavoriteViewHolder(
        itemView: View,
        private val onClick: (FavoriteItem) -> Unit,
        private val onSelect: (Long) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val nameView: TextView = itemView.findViewById(R.id.textFavoriteName)
        private val coordView: TextView = itemView.findViewById(R.id.textFavoriteCoords)

        fun bind(item: FavoriteItem, selectedId: Long?) {
            nameView.text = item.name
            coordView.text = "${item.lat}, ${item.lng}"
            itemView.isSelected = item.id == selectedId
            itemView.setOnClickListener {
                onSelect(item.id)
                onClick(item)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<FavoriteItem>() {
        override fun areItemsTheSame(oldItem: FavoriteItem, newItem: FavoriteItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FavoriteItem, newItem: FavoriteItem): Boolean {
            return oldItem == newItem
        }
    }
}
