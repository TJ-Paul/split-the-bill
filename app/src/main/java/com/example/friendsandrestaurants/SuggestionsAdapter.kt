package com.example.friendsandrestaurants

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.friendsandrestaurants.databinding.ItemSuggestionBinding

/**
 * Saved friend names for "Recent". Names already on the bill are shown as added and can't be
 * selected again. Supports a search filter.
 */
class SuggestionsAdapter(
    private var suggestions: List<String>,
    private val alreadyAdded: Set<String>,
    private val onRemoveClicked: (String) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<SuggestionsAdapter.SuggestionViewHolder>() {

    val selectedNames = linkedSetOf<String>()
    private var query = ""
    private var visible: List<String> = suggestions

    private fun isAdded(name: String) = name.lowercase() in alreadyAdded

    fun updateData(newSuggestions: List<String>) {
        suggestions = newSuggestions
        // Keep selection only for names that still exist
        selectedNames.retainAll(suggestions.toSet())
        applyFilter()
        onSelectionChanged(selectedNames.size)
    }

    fun filter(text: String) {
        query = text.trim()
        applyFilter()
    }

    val visibleCount: Int get() = visible.size

    private val selectableVisible: List<String> get() = visible.filterNot(::isAdded)

    val allVisibleSelected: Boolean
        get() = selectableVisible.let { it.isNotEmpty() && selectedNames.containsAll(it) }

    fun setAllVisibleSelected(selected: Boolean) {
        if (selected) selectedNames.addAll(selectableVisible) else selectedNames.removeAll(selectableVisible.toSet())
        notifyItemRangeChanged(0, visible.size)
        onSelectionChanged(selectedNames.size)
    }

    @Suppress("NotifyDataSetChanged")
    private fun applyFilter() {
        visible = if (query.isEmpty()) suggestions else suggestions.filter { it.contains(query, ignoreCase = true) }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val name = visible[position]
        val added = isAdded(name)
        val b = holder.binding
        b.tvName.text = name
        b.tvAvatar.text = OrderAdapter.initials(name)
        b.tvAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(b.root.context, OrderAdapter.avatarColorRes(name))
        )
        b.tvInList.isVisible = added

        b.checkBox.setOnCheckedChangeListener(null)
        b.checkBox.isEnabled = !added
        b.checkBox.isChecked = added || selectedNames.contains(name)
        b.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedNames.add(name) else selectedNames.remove(name)
            onSelectionChanged(selectedNames.size)
        }

        b.root.isEnabled = !added
        b.root.alpha = if (added) 0.6f else 1f
        b.root.setOnClickListener {
            if (!added) b.checkBox.toggle()
        }

        b.btnRemove.setOnClickListener {
            onRemoveClicked(name)
        }
    }

    override fun getItemCount(): Int = visible.size

    class SuggestionViewHolder(val binding: ItemSuggestionBinding) : RecyclerView.ViewHolder(binding.root)
}
