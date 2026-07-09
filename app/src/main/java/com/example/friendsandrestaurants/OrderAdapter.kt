package com.example.friendsandrestaurants

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.friendsandrestaurants.data.Order
import com.example.friendsandrestaurants.databinding.ItemOrderBinding

class OrderAdapter(
    private val onOrderUpdated: (Order) -> Unit,
    private val onOrderRemoved: (Order) -> Unit
) : ListAdapter<Order, OrderAdapter.OrderViewHolder>(OrderDiffCallback()) {

    private var foodItemSuggestions: List<String> = emptyList()

    fun updateSuggestions(newSuggestions: List<String>) {
        foodItemSuggestions = newSuggestions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class OrderViewHolder(private val binding: ItemOrderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var currentOrder: Order? = null
        private var isBinding = false

        init {
            binding.etFoodItem.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!isBinding) {
                        currentOrder?.let {
                            it.foodItem = s.toString()
                            onOrderUpdated(it)
                        }
                    }
                }
            })

            binding.etPrice.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!isBinding) {
                        currentOrder?.let {
                            it.price = s.toString().toDoubleOrNull() ?: 0.0
                            updateCashback(it)
                            onOrderUpdated(it)
                        }
                    }
                }
            })

            binding.etPaid.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!isBinding) {
                        currentOrder?.let {
                            val newValue = s.toString().toDoubleOrNull() ?: 0.0
                            it.paid = newValue
                            if (!it.isDone) {
                                it.previousPaid = newValue
                            }
                            updateCashback(it)
                            onOrderUpdated(it)
                        }
                    }
                }
            })

            binding.statusChip.setOnClickListener {
                currentOrder?.let {
                    it.isDone = !it.isDone
                    if (it.isDone) {
                        it.previousPaid = it.paid
                        it.paid = it.price
                    } else {
                        it.paid = it.previousPaid
                    }
                    
                    isBinding = true
                    try {
                        val paidText = if (it.paid == 0.0) "" else String.format("%.0f", it.paid)
                        binding.etPaid.setText(paidText)
                        
                        updateStatusChip(it)
                        updateCashback(it)
                        onOrderUpdated(it)
                    } finally {
                        isBinding = false
                    }
                }
            }

            binding.root.setOnLongClickListener {
                currentOrder?.let { onOrderRemoved(it) }
                true
            }
        }

        fun bind(order: Order) {
            isBinding = true
            try {
                currentOrder = order
                binding.tvFriendName.text = order.friendName
                binding.tvAvatar.text = order.friendName.firstOrNull()?.toString()?.uppercase() ?: "?"
                
                val adapter = ArrayAdapter(
                    binding.root.context,
                    android.R.layout.simple_dropdown_item_1line,
                    foodItemSuggestions
                )
                binding.etFoodItem.setAdapter(adapter)

                if (binding.etFoodItem.text.toString() != order.foodItem) {
                    binding.etFoodItem.setText(order.foodItem, false)
                }
                
                val priceText = if (order.price == 0.0) "" else String.format("%.0f", order.price)
                if (binding.etPrice.text.toString() != priceText) {
                    binding.etPrice.setText(priceText)
                }
                
                val paidText = if (order.paid == 0.0) "" else String.format("%.0f", order.paid)
                if (binding.etPaid.text.toString() != paidText) {
                    binding.etPaid.setText(paidText)
                }
                
                updateStatusChip(order)
                updateCashback(order)
            } finally {
                isBinding = false
            }
        }

        private fun updateStatusChip(order: Order) {
            if (order.isDone) {
                binding.statusChip.text = "Paid"
                binding.statusChip.setChipBackgroundColorResource(R.color.color_success_container)
                binding.statusChip.setTextColor(binding.root.context.getColor(R.color.palette_green_dark))
                binding.statusChip.setChipIconResource(R.drawable.ic_check_circle)
                binding.statusChip.chipIconTint = android.content.res.ColorStateList.valueOf(
                    binding.root.context.getColor(R.color.palette_green_dark)
                )
                binding.statusChip.isChipIconVisible = true
            } else {
                binding.statusChip.text = "Unpaid"
                binding.statusChip.setChipBackgroundColorResource(R.color.color_secondary_container)
                binding.statusChip.setTextColor(binding.root.context.getColor(R.color.color_secondary_dark))
                binding.statusChip.isChipIconVisible = false
            }
        }

        private fun updateCashback(order: Order) {
            val cb = order.cashback
            val cbInt = cb.toInt()
            val absCb = Math.abs(cbInt)
            
            if (cbInt < 0) {
                binding.tvCashback.text = "Due: $absCb tk"
                binding.tvCashback.setBackgroundResource(R.drawable.bg_pill_secondary)
                binding.tvCashback.setTextColor(binding.root.context.getColor(android.R.color.holo_red_dark))
            } else if (cbInt > 0) {
                binding.tvCashback.text = "Refund: $absCb tk"
                binding.tvCashback.setBackgroundResource(R.drawable.bg_pill_success)
                binding.tvCashback.setTextColor(binding.root.context.getColor(R.color.palette_green_dark))
            } else {
                binding.tvCashback.text = "Settled"
                binding.tvCashback.setBackgroundResource(R.drawable.bg_pill_secondary)
                binding.tvCashback.setTextColor(binding.root.context.getColor(android.R.color.darker_gray))
            }
        }
    }

    class OrderDiffCallback : DiffUtil.ItemCallback<Order>() {
        override fun areItemsTheSame(oldItem: Order, newItem: Order): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Order, newItem: Order): Boolean {
            return oldItem.friendName == newItem.friendName &&
                   oldItem.foodItem == newItem.foodItem &&
                   oldItem.price == newItem.price &&
                   oldItem.paid == newItem.paid &&
                   oldItem.isDone == newItem.isDone
        }
    }
}