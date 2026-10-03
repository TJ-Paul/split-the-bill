package com.example.friendsandrestaurants

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.friendsandrestaurants.data.FoodItem
import com.example.friendsandrestaurants.data.Order
import com.example.friendsandrestaurants.databinding.ItemExtraFoodBinding
import com.example.friendsandrestaurants.databinding.ItemOrderBinding
import com.google.android.material.textfield.TextInputLayout

/**
 * Editable list of friends' orders.
 *
 * Cards edit their [Order] in place and report every change through [onOrderUpdated]:
 * `notifyList = false` while typing (saved, totals refresh, no re-sort) and `true` when an edit is
 * committed (focus leaves a field, a button is pressed), which lets the list re-sort.
 * Diffing runs on the main thread so it never races with those in-place edits.
 */
class OrderAdapter(
    private val onOrderUpdated: (Order, Boolean) -> Unit,
    private val onRemoveRequested: (Order) -> Unit,
    private val onEditNameRequested: (Order) -> Unit
) : ListAdapter<Order, OrderAdapter.OrderViewHolder>(
    AsyncDifferConfig.Builder(OrderDiffCallback())
        .setBackgroundThreadExecutor { it.run() }
        .build()
) {

    private var foodItemSuggestions: List<String> = emptyList()
    private var suggestionsVersion = 0
    private val attachedHolders = LinkedHashSet<OrderViewHolder>()

    fun updateSuggestions(newSuggestions: List<String>) {
        if (newSuggestions == foodItemSuggestions) return
        foodItemSuggestions = newSuggestions
        suggestionsVersion++
        attachedHolders.forEach { it.refreshSuggestionsIfNeeded() }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewAttachedToWindow(holder: OrderViewHolder) {
        attachedHolders.add(holder)
        holder.refreshSuggestionsIfNeeded()
    }

    override fun onViewDetachedFromWindow(holder: OrderViewHolder) {
        attachedHolders.remove(holder)
        // A card being edited scrolled out of view: commit the field (it is still attached at this
        // point) and close the keyboard, which would otherwise stay open with nothing to type into.
        if (holder.itemView.hasFocus()) holder.itemView.clearFocusAndHideKeyboard()
    }

    inner class OrderViewHolder(private val binding: ItemOrderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var order: Order? = null
        private var isBinding = false
        private var boundSuggestionsVersion = -1
        private val extraRows = mutableListOf<ItemExtraFoodBinding>()
        private val ctx get() = binding.root.context

        init {
            binding.etFoodItem.doAfterTextChanged { s ->
                if (isBinding) return@doAfterTextChanged
                val o = order ?: return@doAfterTextChanged
                val item = o.items.firstOrNull() ?: return@doAfterTextChanged
                item.name = s?.toString().orEmpty()
                o.syncItems()
                renderStatus(o)
                onOrderUpdated(o, false)
            }
            binding.etFoodItem.setOnFocusChangeListener { v, hasFocus ->
                if (!hasFocus && !isBinding && v.isAttachedToWindow) order?.let { onOrderUpdated(it, true) }
            }

            PriceCalculator.setupPriceField(
                editText = binding.etPrice,
                textInputLayout = binding.tilPrice,
                getRawExpression = { order?.items?.firstOrNull()?.rawPriceExpression }
            ) { value, raw, committed ->
                if (isBinding) return@setupPriceField
                val o = order ?: return@setupPriceField
                val item = o.items.firstOrNull() ?: return@setupPriceField
                item.price = value
                item.rawPriceExpression = raw
                onAmountsChanged(o, committed)
            }

            PriceCalculator.setupPriceField(
                editText = binding.etPaid,
                textInputLayout = binding.tilPaid,
                getRawExpression = { order?.rawPaidExpression }
            ) { value, raw, committed ->
                if (isBinding) return@setupPriceField
                val o = order ?: return@setupPriceField
                o.paid = value
                o.rawPaidExpression = raw
                if (!o.isDone) o.previousPaid = value
                onAmountsChanged(o, committed)
            }

            binding.btnRemoveItem1.setOnClickListener { removeItem(0) }
            binding.btnAddFood.setOnClickListener { addItem() }
            binding.btnMore.setOnClickListener { showMenu() }
            binding.statusChip.setOnClickListener { togglePaid() }

            val editName = View.OnClickListener { order?.let(onEditNameRequested) }
            binding.nameBlock.setOnClickListener(editName)
            binding.tvAvatar.setOnClickListener(editName)

            val remove = View.OnLongClickListener {
                order?.let(onRemoveRequested)
                true
            }
            binding.card.setOnLongClickListener(remove)
            binding.nameBlock.setOnLongClickListener(remove)
            binding.tvAvatar.setOnLongClickListener(remove)
        }

        fun bind(o: Order) {
            isBinding = true
            try {
                order = o
                binding.tvFriendName.text = o.friendName
                // Orders a guest added from the shared page get a small phone icon.
                val fromGuest = o.ownerToken != null
                binding.tvFriendName.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0, 0, if (fromGuest) R.drawable.ic_guest_badge else 0, 0
                )
                binding.tvFriendName.contentDescription =
                    if (fromGuest) ctx.getString(R.string.guest_order_desc, o.friendName) else null
                binding.tvAvatar.text = initials(o.friendName)
                binding.tvAvatar.backgroundTintList = ColorStateList.valueOf(avatarColor(o.friendName))
                renderItems(o)
                setMoneyIfIdle(binding.etPaid, binding.tilPaid, o.paid)
                renderStatus(o)
                refreshSuggestionsIfNeeded()
            } finally {
                isBinding = false
            }
        }

        fun refreshSuggestionsIfNeeded() {
            if (boundSuggestionsVersion == suggestionsVersion) return
            boundSuggestionsVersion = suggestionsVersion
            binding.etFoodItem.applySuggestions(foodItemSuggestions)
            extraRows.forEach { it.etExtraFoodName.applySuggestions(foodItemSuggestions) }
        }

        // ------------------------------------------------------------ actions

        private fun onAmountsChanged(o: Order, committed: Boolean) {
            o.syncItems()
            if (committed) o.reconcilePaidStatus()
            renderStatus(o)
            onOrderUpdated(o, committed)
        }

        private fun addItem() {
            val o = order ?: return
            val first = o.items.firstOrNull()
            // A friend added by name only has one empty item: fill that in instead of adding another.
            if (o.items.size == 1 && first != null && first.name.isBlank() && Order.isZero(first.price)) {
                focusAndShowKeyboard(binding.etFoodItem)
                return
            }
            binding.root.findFocus()?.clearFocus()
            o.items.add(FoodItem())
            o.syncItems()
            renderItems(o)
            renderStatus(o)
            onOrderUpdated(o, true)
            extraRows.lastOrNull()?.let { focusAndShowKeyboard(it.etExtraFoodName) }
        }

        private fun removeItem(index: Int) {
            val o = order ?: return
            if (o.items.size <= 1 || index !in o.items.indices) return
            // Commit any field being edited first; rows are positional and are about to shift.
            binding.root.clearFocusAndHideKeyboard()
            o.items.removeAt(index)
            o.syncItems()
            o.reconcilePaidStatus()
            renderItems(o)
            isBinding = true
            try {
                setMoneyIfIdle(binding.etPaid, binding.tilPaid, o.paid)
            } finally {
                isBinding = false
            }
            renderStatus(o)
            onOrderUpdated(o, true)
        }

        private fun togglePaid() {
            val o = order ?: return
            binding.root.clearFocusAndHideKeyboard()
            o.togglePaid()
            isBinding = true
            try {
                binding.etPaid.setText(PriceCalculator.fieldText(o.paid))
                binding.tilPaid.error = null
                binding.tilPaid.isErrorEnabled = false
            } finally {
                isBinding = false
            }
            renderStatus(o)
            onOrderUpdated(o, true)
        }

        private fun showMenu() {
            val o = order ?: return
            val popup = PopupMenu(ctx, binding.btnMore)
            popup.inflate(R.menu.menu_order_item)
            popup.setForceShowIcon(true)
            popup.menu.findItem(R.id.action_toggle_paid)?.setTitle(
                if (o.isDone) R.string.menu_mark_unpaid else R.string.menu_mark_paid
            )
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit_name -> onEditNameRequested(o)
                    R.id.action_add_item -> addItem()
                    R.id.action_toggle_paid -> togglePaid()
                    R.id.action_remove -> onRemoveRequested(o)
                }
                true
            }
            popup.show()
        }

        // ------------------------------------------------------------ rendering

        private fun renderItems(o: Order) {
            val wasBinding = isBinding
            isBinding = true
            try {
                o.syncItems()
                val multi = o.items.size > 1
                val first = o.items[0]

                binding.tilFoodItem.hint = if (multi) ctx.getString(R.string.food_hint_numbered, 1) else ctx.getString(R.string.food_hint)
                setTextIfIdle(binding.etFoodItem, first.name)
                setMoneyIfIdle(binding.etPrice, binding.tilPrice, first.price)
                binding.btnRemoveItem1.isVisible = multi
                placePaidField(multi)
                binding.llFooter.isVisible = multi
                binding.tvTotal.text = ctx.getString(R.string.total_label, PriceCalculator.formatMoneyWithUnit(o.price))

                val extraCount = o.items.size - 1
                while (extraRows.size > extraCount) {
                    val row = extraRows.removeAt(extraRows.lastIndex)
                    binding.llExtraFoodItems.removeView(row.root)
                }
                while (extraRows.size < extraCount) {
                    extraRows.add(createExtraRow(extraRows.size))
                }
                extraRows.forEachIndexed { j, row ->
                    val item = o.items[j + 1]
                    row.tilExtraFoodName.hint = ctx.getString(R.string.food_hint_numbered, j + 2)
                    setTextIfIdle(row.etExtraFoodName, item.name)
                    setMoneyIfIdle(row.etExtraFoodPrice, row.tilExtraFoodPrice, item.price)
                }
                binding.llExtraFoodItems.isVisible = extraCount > 0
            } finally {
                isBinding = wasBinding
            }
        }

        /** Rows are positional: row [rowIndex] always edits `items[rowIndex + 1]` of the bound order. */
        private fun createExtraRow(rowIndex: Int): ItemExtraFoodBinding {
            val itemIndex = rowIndex + 1
            val row = ItemExtraFoodBinding.inflate(LayoutInflater.from(ctx), binding.llExtraFoodItems, false)
            row.etExtraFoodName.applySuggestions(foodItemSuggestions)

            row.etExtraFoodName.doAfterTextChanged { s ->
                if (isBinding) return@doAfterTextChanged
                val o = order ?: return@doAfterTextChanged
                val item = o.items.getOrNull(itemIndex) ?: return@doAfterTextChanged
                item.name = s?.toString().orEmpty()
                o.syncItems()
                renderStatus(o)
                onOrderUpdated(o, false)
            }
            row.etExtraFoodName.setOnFocusChangeListener { v, hasFocus ->
                if (!hasFocus && !isBinding && v.isAttachedToWindow) order?.let { onOrderUpdated(it, true) }
            }

            PriceCalculator.setupPriceField(
                editText = row.etExtraFoodPrice,
                textInputLayout = row.tilExtraFoodPrice,
                getRawExpression = { order?.items?.getOrNull(itemIndex)?.rawPriceExpression }
            ) { value, raw, committed ->
                if (isBinding) return@setupPriceField
                val o = order ?: return@setupPriceField
                val item = o.items.getOrNull(itemIndex) ?: return@setupPriceField
                item.price = value
                item.rawPriceExpression = raw
                onAmountsChanged(o, committed)
            }

            row.btnRemoveExtraFood.setOnClickListener { removeItem(itemIndex) }
            binding.llExtraFoodItems.addView(row.root)
            return row
        }

        /** Single item: Paid sits in the first row. Several items: it moves under the prices, next to the total. */
        private fun placePaidField(multi: Boolean) {
            val target = if (multi) binding.llFooter else binding.llFoodItem1Row
            val paid = binding.tilPaid
            if (paid.parent === target) return
            (paid.parent as? ViewGroup)?.removeView(paid)
            if (multi) target.addView(paid, 1) else target.addView(paid)
        }

        private fun renderStatus(o: Order) {
            val cb = o.cashback
            val (text, colorRes, stripeRes) = when {
                o.isEmpty -> Triple(ctx.getString(R.string.status_no_items), R.color.status_settled, R.color.status_empty_stripe)
                Order.isZero(o.price) && Order.isZero(o.paid) ->
                    Triple(ctx.getString(R.string.status_no_price), R.color.status_settled, R.color.status_empty_stripe)
                cb < 0 -> Triple(ctx.getString(R.string.status_due, PriceCalculator.formatMoneyWithUnit(-cb)), R.color.status_due, R.color.status_due)
                cb > 0 -> Triple(ctx.getString(R.string.status_refund, PriceCalculator.formatMoneyWithUnit(cb)), R.color.status_refund, R.color.status_refund)
                else -> Triple(ctx.getString(R.string.status_settled), R.color.status_settled, R.color.palette_teal)
            }
            val itemCount = o.items.count { it.name.isNotBlank() || !Order.isZero(it.price) }
            binding.tvCashback.text = if (itemCount > 1) {
                "$text · ${ctx.resources.getQuantityString(R.plurals.item_count, itemCount, itemCount)}"
            } else text
            binding.tvCashback.setTextColor(ContextCompat.getColor(ctx, colorRes))
            binding.statusStripe.setBackgroundColor(ContextCompat.getColor(ctx, stripeRes))
            binding.tvTotal.text = ctx.getString(R.string.total_label, PriceCalculator.formatMoneyWithUnit(o.price))

            val chip = binding.statusChip
            if (o.isDone) {
                chip.setText(R.string.chip_paid)
                chip.setChipBackgroundColorResource(R.color.status_refund_container)
                chip.setTextColor(ContextCompat.getColor(ctx, R.color.status_refund))
                chip.setChipIconResource(R.drawable.ic_check_circle)
                chip.chipIconTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_refund))
                chip.isChipIconVisible = true
            } else {
                chip.setText(R.string.chip_unpaid)
                chip.setChipBackgroundColorResource(R.color.color_secondary_container)
                chip.setTextColor(ContextCompat.getColor(ctx, R.color.color_secondary_dark))
                chip.isChipIconVisible = false
            }
        }

        private fun setTextIfIdle(view: AutoCompleteTextView, text: String) {
            if (!view.hasFocus() && view.text.toString() != text) view.setText(text, false)
        }

        private fun setMoneyIfIdle(view: EditText, til: TextInputLayout, value: Double) {
            if (view.hasFocus()) return
            val text = PriceCalculator.fieldText(value)
            if (view.text.toString() != text) view.setText(text)
            if (til.error != null) {
                til.error = null
                til.isErrorEnabled = false
            }
        }

        private fun focusAndShowKeyboard(view: EditText) {
            view.post {
                if (!view.isAttachedToWindow) return@post
                view.requestFocus()
                view.setSelection(view.text.length)
                val imm = ctx.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    class OrderDiffCallback : DiffUtil.ItemCallback<Order>() {
        override fun areItemsTheSame(oldItem: Order, newItem: Order): Boolean = oldItem.id == newItem.id

        // Data-class equality compares every field including the item list.
        override fun areContentsTheSame(oldItem: Order, newItem: Order): Boolean = oldItem == newItem
    }

    companion object {
        private val AVATAR_COLORS = intArrayOf(
            R.color.avatar_1, R.color.avatar_2, R.color.avatar_3,
            R.color.avatar_4, R.color.avatar_5, R.color.avatar_6
        )

        fun initials(name: String): String {
            val words = name.split(Regex("[\\s,.\\-]+")).filter { w -> w.any { it.isLetterOrDigit() } }
            val letters = words.take(2).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            return if (letters.isEmpty()) "?" else letters.joinToString("")
        }

        fun avatarColorRes(name: String): Int =
            AVATAR_COLORS[Math.floorMod(name.lowercase().hashCode(), AVATAR_COLORS.size)]
    }

    private fun OrderViewHolder.avatarColor(name: String): Int =
        ContextCompat.getColor(itemView.context, avatarColorRes(name))
}

/** Sets or refreshes the dropdown suggestions on an autocomplete field without replacing its adapter. */
fun AutoCompleteTextView.applySuggestions(suggestions: List<String>) {
    @Suppress("UNCHECKED_CAST")
    val existing = adapter as? ArrayAdapter<String>
    if (existing == null) {
        setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, ArrayList(suggestions)))
    } else {
        existing.setNotifyOnChange(false)
        existing.clear()
        existing.addAll(suggestions)
        existing.notifyDataSetChanged()
    }
}
