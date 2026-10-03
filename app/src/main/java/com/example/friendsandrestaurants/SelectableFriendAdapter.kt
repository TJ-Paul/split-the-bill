package com.example.friendsandrestaurants

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.friendsandrestaurants.data.Order
import com.example.friendsandrestaurants.databinding.ItemSelectableFriendBinding

/** Checklist of friends on the current bill, keyed by order id so duplicate names are handled. */
class SelectableFriendAdapter(
    private val friends: List<Order>,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<SelectableFriendAdapter.ViewHolder>() {

    val selectedIds = linkedSetOf<String>()

    val allSelected: Boolean get() = friends.isNotEmpty() && selectedIds.size == friends.size

    fun setAllSelected(selected: Boolean) {
        selectedIds.clear()
        if (selected) friends.forEach { selectedIds.add(it.id) }
        notifyItemRangeChanged(0, friends.size)
        onSelectionChanged(selectedIds.size)
    }

    class ViewHolder(val binding: ItemSelectableFriendBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectableFriendBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val friend = friends[position]
        val checkBox = holder.binding.checkBoxFriend
        // Detach the previous row's listener before changing the checked state, or a recycled
        // view would toggle the wrong friend.
        checkBox.setOnCheckedChangeListener(null)
        checkBox.text = friend.friendName
        checkBox.isChecked = friend.id in selectedIds
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedIds.add(friend.id) else selectedIds.remove(friend.id)
            onSelectionChanged(selectedIds.size)
        }
    }

    override fun getItemCount(): Int = friends.size
}
