package com.sahed.document_viewer

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil

class RecentAdapter(
    private var items: List<RecentItem>,
    private val onClick: (RecentItem) -> Unit
) : RecyclerView.Adapter<RecentAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.recentName)
        val detail: TextView = view.findViewById(R.id.recentDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
        DesignSystem.bouncyPress(view)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.detail.text = if (item.pageCount > 0)
            holder.itemView.context.getString(R.string.recent_progress, item.page + 1, item.pageCount) else "PDF · Stored on your device"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<RecentItem>) {
        if (items == newItems) return
        val previous = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(old: Int, new: Int) = previous[old].uri == newItems[new].uri
            override fun areContentsTheSame(old: Int, new: Int) = previous[old] == newItems[new]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }
}
