package com.example.friendsandrestaurants

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.friendsandrestaurants.databinding.ItemSelectableFriendBinding

class SelectableFriendAdapter(
    private val friends: List<String>
) : RecyclerView.Adapter<SelectableFriendAdapter.ViewHolder>() {

    val selectedFriends = mutableSetOf<String>()

    class ViewHolder(val binding: ItemSelectableFriendBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectableFriendBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val friendName = friends[position]
        holder.binding.checkBoxFriend.text = friendName
        holder.binding.checkBoxFriend.isChecked = selectedFriends.contains(friendName)

        holder.binding.checkBoxFriend.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedFriends.add(friendName)
            } else {
                selectedFriends.remove(friendName)
            }
        }
    }

    override fun getItemCount(): Int = friends.size
}
