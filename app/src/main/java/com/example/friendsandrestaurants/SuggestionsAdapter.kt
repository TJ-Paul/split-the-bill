package com.example.friendsandrestaurants

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.friendsandrestaurants.databinding.ItemSuggestionBinding

class SuggestionsAdapter(
    private var suggestions: List<String>,
    private val onRemoveClicked: (String) -> Unit
) : RecyclerView.Adapter<SuggestionsAdapter.SuggestionViewHolder>() {

    val selectedNames = mutableSetOf<String>()

    fun updateData(newSuggestions: List<String>) {
        suggestions = newSuggestions
        // Keep selection only for names that still exist
        val iterator = selectedNames.iterator()
        while (iterator.hasNext()) {
            if (!suggestions.contains(iterator.next())) {
                iterator.remove()
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val name = suggestions[position]
        holder.binding.tvName.text = name
        
        holder.binding.checkBox.setOnCheckedChangeListener(null)
        holder.binding.checkBox.isChecked = selectedNames.contains(name)
        
        holder.binding.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedNames.add(name) else selectedNames.remove(name)
        }

        holder.itemView.setOnClickListener {
            holder.binding.checkBox.toggle()
        }

        holder.binding.btnRemove.setOnClickListener {
            onRemoveClicked(name)
        }
    }

    override fun getItemCount(): Int = suggestions.size

    class SuggestionViewHolder(val binding: ItemSuggestionBinding) : RecyclerView.ViewHolder(binding.root)
}