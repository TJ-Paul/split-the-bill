package com.example.friendsandrestaurants

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.friendsandrestaurants.databinding.ItemHistoryBinding

class HistoryAdapter(
    private val logs: List<String>,
    private val onLogClicked: (String) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val entries = logs.map { LogEntry.parse(it) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val entry = entries[position]
        val ctx = holder.binding.root.context

        holder.binding.tvRestaurant.text = entry.restaurant.ifBlank { ctx.getString(R.string.unknown_restaurant) }
        holder.binding.tvDate.text = entry.displayDate
        holder.binding.tvDate.isVisible = entry.displayDate.isNotBlank()
        holder.binding.tvTotal.text = entry.total?.let { ctx.getString(R.string.money_tk, it) }
        holder.binding.tvTotal.isVisible = entry.total != null

        holder.itemView.setOnClickListener { onLogClicked(entry.raw) }
    }

    override fun getItemCount(): Int = entries.size

    class HistoryViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)
}
