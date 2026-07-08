package com.example.friendsandrestaurants

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.friendsandrestaurants.databinding.ItemHistoryBinding

class HistoryAdapter(
    private val logs: List<String>,
    private val onLogClicked: (String) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val log = logs[position]
        val title = log.substringBefore("\n")
        
        // title format is "[timestamp] Restaurant"
        val timestamp = title.substringAfter("[").substringBefore("]")
        val restaurant = title.substringAfter("] ").trim()
        
        holder.binding.tvDate.text = timestamp
        holder.binding.tvRestaurant.text = restaurant
        
        holder.itemView.setOnClickListener { onLogClicked(log) }
    }

    override fun getItemCount(): Int = logs.size

    class HistoryViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)
}