package com.example.friendsandrestaurants

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.example.friendsandrestaurants.PriceCalculator.formatMoneyWithUnit
import com.example.friendsandrestaurants.data.FoodItem
import com.example.friendsandrestaurants.data.Order
import com.example.friendsandrestaurants.databinding.DialogAddFriendFlowBinding
import com.example.friendsandrestaurants.databinding.DialogBulkAddBinding
import com.example.friendsandrestaurants.databinding.DialogEditNameBinding
import com.example.friendsandrestaurants.databinding.DialogHistoryDetailBinding
import com.example.friendsandrestaurants.databinding.DialogListBinding
import com.example.friendsandrestaurants.databinding.DialogQuickAddFoodBinding
import com.example.friendsandrestaurants.databinding.FragmentFirstBinding
import com.example.friendsandrestaurants.databinding.ItemExtraFoodBinding
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OrderViewModel by activityViewModels()

    private lateinit var adapter: OrderAdapter
    private var imeVisible = false
    private var pendingScrollToId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMenu()
        setupList()
        setupRestaurantField()

        binding.btnAddFriend.setOnClickListener { showAddFriendFlowDialog() }
        binding.btnQuickAdd.setOnClickListener { showQuickAddDialog() }
        binding.btnBulkAdd.setOnClickListener { showBulkAddDialog() }
        binding.fabAddFood.setOnClickListener { showQuickAddFoodDialog() }
        binding.btnReceipt.setOnClickListener { openReceipt() }

        // Hide the summary bar and FAB while typing so more of the list fits above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinator) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (visible != imeVisible) {
                imeVisible = visible
                binding.summaryBar.isVisible = !visible
                updateFab()
            }
            insets
        }

        viewModel.summary.observe(viewLifecycleOwner) { renderSummary(it) }
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
                R.id.action_history -> { showSavedLogsDialog(); true }
                R.id.action_clear -> { showWipeConfirmationDialog(); true }
                else -> false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupList() {
        adapter = OrderAdapter(
            onOrderUpdated = { order, notifyList -> viewModel.updateOrder(order, notifyList) },
            onRemoveRequested = { order -> removeWithUndo(order) },
            onEditNameRequested = { order -> showEditNameDialog(order) }
        )

        binding.rvOrders.layoutManager = LinearLayoutManager(context)
        binding.rvOrders.adapter = adapter
        // Change animations swap in a new view holder, which steals focus from the field being typed in.
        (binding.rvOrders.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.rvOrders.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8 && binding.fabAddFood.isExtended) binding.fabAddFood.shrink()
                else if (dy < -8 && !binding.fabAddFood.isExtended) binding.fabAddFood.extend()
            }
        })

        viewModel.orders.observe(viewLifecycleOwner) { orders ->
            adapter.submitList(orders) {
                val b = _binding ?: return@submitList
                val id = pendingScrollToId ?: return@submitList
                pendingScrollToId = null
                val pos = orders.indexOfFirst { it.id == id }
                if (pos >= 0) b.rvOrders.smoothScrollToPosition(pos)
            }
            binding.emptyState.isVisible = orders.isEmpty()
            updateFab()
        }

        viewModel.allUniqueFoodItems.observe(viewLifecycleOwner) { items ->
            adapter.updateSuggestions(items.toList())
        }
    }

    private fun setupRestaurantField() {
        binding.etRestaurant.setText(viewModel.restaurantName)
        binding.etRestaurant.doAfterTextChanged { viewModel.updateRestaurantName(it?.toString().orEmpty()) }
        binding.etRestaurant.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocusAndHideKeyboard()
                true
            } else false
        }
    }

    private fun updateFab() {
        val b = _binding ?: return
        val hasFriends = viewModel.currentOrders.isNotEmpty()
        if (hasFriends && !imeVisible) b.fabAddFood.show() else b.fabAddFood.hide()
    }

    private fun renderSummary(s: BillSummary) {
        val b = _binding ?: return
        val ctx = requireContext()
        b.tvSummaryLabel.text = if (s.friendCount > 0) {
            "${resources.getQuantityString(R.plurals.friend_count, s.friendCount, s.friendCount)} · ${getString(R.string.summary_total_label)}"
        } else getString(R.string.summary_total_label)
        b.tvSummaryTotal.text = formatMoneyWithUnit(s.totalBill)

        val status = SpannableStringBuilder()
        fun append(text: String, colorRes: Int?) {
            if (status.isNotEmpty()) status.append(" · ")
            val start = status.length
            status.append(text)
            if (colorRes != null) {
                status.setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, colorRes)), start, status.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        when {
            s.friendCount == 0 || (Order.isZero(s.totalBill) && Order.isZero(s.totalPaid)) ->
                append(getString(R.string.summary_nothing_yet), null)
            s.isAllSettled -> {
                append(getString(R.string.summary_paid, formatMoneyWithUnit(s.totalPaid)), null)
                append("${getString(R.string.summary_all_settled)} ✓", R.color.status_refund)
            }
            else -> {
                append(getString(R.string.summary_paid, formatMoneyWithUnit(s.totalPaid)), null)
                if (!Order.isZero(s.totalDue)) append(getString(R.string.summary_due, formatMoneyWithUnit(s.totalDue)), R.color.status_due)
                if (!Order.isZero(s.totalRefund)) append(getString(R.string.summary_refund, formatMoneyWithUnit(s.totalRefund)), R.color.status_refund)
            }
        }
        b.tvSummaryStatus.text = status
        b.btnReceipt.isEnabled = s.friendCount > 0
    }

    private fun openReceipt() {
        // Commit whatever field is being edited so the receipt includes it.
        binding.root.clearFocusAndHideKeyboard()
        val nav = findNavController()
        if (nav.currentDestination?.id == R.id.FirstFragment) {
            nav.navigate(R.id.action_FirstFragment_to_SecondFragment)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun snackbar(text: CharSequence, duration: Int = Snackbar.LENGTH_LONG): Snackbar {
        val bar = Snackbar.make(binding.coordinator, text, duration)
        val anchor = when {
            binding.fabAddFood.isVisible -> binding.fabAddFood
            binding.summaryBar.isVisible -> binding.summaryBar
            else -> null
        }
        if (anchor != null) bar.anchorView = anchor
        return bar
    }

    /** Shows the keyboard for [field] as soon as the dialog appears. */
    private fun AlertDialog.focusOnShow(field: View) {
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        field.requestFocus()
    }

    private fun TextInputLayout.clearError() {
        error = null
        isErrorEnabled = false
    }

    // ------------------------------------------------------------------ remove / clear

    private fun removeWithUndo(order: Order) {
        val removed = viewModel.removeOrder(order.id) ?: return
        snackbar(getString(R.string.removed_friend, removed.friendName))
            .setAction(R.string.undo) { viewModel.restoreOrder(removed) }
            .show()
    }

    private fun showWipeConfirmationDialog() {
        val ctx = requireContext()
        val checkBox = MaterialCheckBox(ctx).apply {
            setText(R.string.clear_also_restaurant)
            isChecked = false
            isVisible = viewModel.restaurantName.isNotBlank()
        }
        val container = FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(checkBox)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_body)
            .setView(container)
            .setPositiveButton(R.string.clear_confirm) { _, _ ->
                val clearRestaurant = checkBox.isChecked
                val snapshot = viewModel.clearOrders(clearRestaurant)
                if (clearRestaurant) binding.etRestaurant.setText("")
                snackbar(getString(R.string.cleared))
                    .setAction(R.string.undo) {
                        viewModel.restoreBill(snapshot)
                        _binding?.etRestaurant?.setText(snapshot.second)
                    }
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ edit name

    private fun showEditNameDialog(order: Order) {
        val ctx = requireContext()
        val d = DialogEditNameBinding.inflate(layoutInflater)
        val input = d.etName
        val til = d.tilName
        input.setText(order.friendName)
        input.setSelection(order.friendName.length)
        val container = d.root

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.edit_name_title)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun save() {
            if (viewModel.renameOrder(order.id, input.text.toString())) {
                dialog.dismiss()
            } else {
                til.error = getString(R.string.name_required)
            }
        }

        input.doAfterTextChanged { til.clearError() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { save() }
        }
        dialog.focusOnShow(input)
        dialog.show()
    }

    // ------------------------------------------------------------------ add item to many

    private fun showQuickAddFoodDialog() {
        val currentFriends = viewModel.currentOrders.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.friendName })
        if (currentFriends.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.no_friends_title)
                .setMessage(R.string.no_friends_body)
                .setPositiveButton(R.string.action_add_friend) { _, _ -> showAddFriendFlowDialog() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val d = DialogQuickAddFoodBinding.inflate(layoutInflater)
        d.etFoodName.applySuggestions(viewModel.allUniqueFoodItems.value.orEmpty())

        var price = 0.0
        var rawPrice: String? = null
        lateinit var adapter: SelectableFriendAdapter

        fun isSplit() = d.toggleMode.checkedButtonId == R.id.btnModeSplit

        fun updatePreview() {
            val n = adapter.selectedIds.size
            d.tilPrice.hint = getString(if (isSplit()) R.string.mode_split else R.string.mode_each)
            d.tilPrice.helperText = when {
                n == 0 || Order.isZero(price) -> null
                isSplit() -> getString(R.string.preview_split, formatMoneyWithUnit(price), n, formatMoneyWithUnit(price / n))
                else -> getString(R.string.preview_each, n, formatMoneyWithUnit(price), formatMoneyWithUnit(price * n))
            }
        }

        adapter = SelectableFriendAdapter(currentFriends) {
            d.cbSelectAll.isChecked = adapter.allSelected
            updatePreview()
        }
        d.rvFriends.layoutManager = LinearLayoutManager(requireContext())
        d.rvFriends.adapter = adapter
        d.cbSelectAll.setOnClickListener { adapter.setAllSelected(d.cbSelectAll.isChecked) }
        d.cbSelectAll.isVisible = currentFriends.size > 1

        PriceCalculator.setupPriceField(d.etPrice, d.tilPrice, { rawPrice }) { value, raw, _ ->
            price = value
            rawPrice = raw
            updatePreview()
        }
        d.toggleMode.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) updatePreview() }
        d.etFoodName.doAfterTextChanged { d.tilFoodName.clearError() }
        updatePreview()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.quick_add_food_title)
            .setView(d.root)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val food = d.etFoodName.text.toString()
                val priceResult = PriceCalculator.evaluate(d.etPrice.text.toString())
                when {
                    food.isBlank() -> {
                        d.tilFoodName.error = getString(R.string.food_required)
                        d.etFoodName.requestFocus()
                    }
                    priceResult.isFailure -> d.tilPrice.error = getString(R.string.invalid_calculation)
                    adapter.selectedIds.isEmpty() ->
                        Toast.makeText(requireContext(), R.string.select_at_least_one, Toast.LENGTH_SHORT).show()
                    else -> {
                        val value = priceResult.getOrDefault(0.0)
                        val raw = PriceCalculator.resolveRawExpression(d.etPrice.text.toString(), value, rawPrice)
                        viewModel.addFoodToFriends(adapter.selectedIds.toList(), food, value, raw, isSplit())
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.focusOnShow(d.etFoodName)
        dialog.show()
    }

    // ------------------------------------------------------------------ add friend

    private fun showAddFriendFlowDialog() {
        val d = DialogAddFriendFlowBinding.inflate(layoutInflater)
        val foodSuggestions = viewModel.allUniqueFoodItems.value.orEmpty()
        d.etName.applySuggestions(viewModel.allUniqueNames.value.orEmpty())
        d.etFood.applySuggestions(foodSuggestions)

        val firstItem = FoodItem()
        val extraItems = mutableListOf<FoodItem>()
        val extraRows = mutableListOf<ItemExtraFoodBinding>()
        var paid = 0.0
        var rawPaid: String? = null
        var paidBeforeFull = ""
        var suppress = false

        fun total() = firstItem.price + extraItems.sumOf { it.price }

        fun syncPaidInFull() {
            if (!d.cbPaidInFull.isChecked) return
            val text = PriceCalculator.fieldText(total())
            if (d.etPaid.text.toString() != text) d.etPaid.setText(text)
        }

        fun updateTotal() {
            val multi = extraItems.isNotEmpty()
            d.tvTotal.isVisible = multi
            d.tvTotal.text = getString(R.string.total_label, formatMoneyWithUnit(total()))
            d.btnRemoveItem1.isVisible = multi
            d.tilFood.hint = if (multi) getString(R.string.food_hint_numbered, 1) else getString(R.string.food_hint)
            syncPaidInFull()
        }

        fun renderRows() {
            suppress = true
            try {
                while (extraRows.size > extraItems.size) {
                    d.llExtraFoodItems.removeView(extraRows.removeAt(extraRows.lastIndex).root)
                }
                while (extraRows.size < extraItems.size) {
                    val index = extraRows.size
                    val row = ItemExtraFoodBinding.inflate(layoutInflater, d.llExtraFoodItems, false)
                    row.etExtraFoodName.applySuggestions(foodSuggestions)
                    row.etExtraFoodName.doAfterTextChanged { s ->
                        if (!suppress) extraItems.getOrNull(index)?.name = s?.toString().orEmpty()
                    }
                    PriceCalculator.setupPriceField(
                        row.etExtraFoodPrice, row.tilExtraFoodPrice,
                        { extraItems.getOrNull(index)?.rawPriceExpression }
                    ) { value, raw, _ ->
                        if (suppress) return@setupPriceField
                        val item = extraItems.getOrNull(index) ?: return@setupPriceField
                        item.price = value
                        item.rawPriceExpression = raw
                        updateTotal()
                    }
                    row.btnRemoveExtraFood.setOnClickListener {
                        d.root.findFocus()?.clearFocus()
                        if (index in extraItems.indices) extraItems.removeAt(index)
                        renderRows()
                    }
                    d.llExtraFoodItems.addView(row.root)
                    extraRows.add(row)
                }
                extraRows.forEachIndexed { j, row ->
                    val item = extraItems[j]
                    row.tilExtraFoodName.hint = getString(R.string.food_hint_numbered, j + 2)
                    if (row.etExtraFoodName.text.toString() != item.name) row.etExtraFoodName.setText(item.name, false)
                    val priceText = PriceCalculator.fieldText(item.price)
                    if (row.etExtraFoodPrice.text.toString() != priceText) row.etExtraFoodPrice.setText(priceText)
                    row.tilExtraFoodPrice.clearError()
                }
                d.llExtraFoodItems.isVisible = extraItems.isNotEmpty()
            } finally {
                suppress = false
            }
            updateTotal()
        }

        d.etFood.doAfterTextChanged { s -> if (!suppress) firstItem.name = s?.toString().orEmpty() }
        PriceCalculator.setupPriceField(d.etPrice, d.tilPrice, { firstItem.rawPriceExpression }) { value, raw, _ ->
            if (suppress) return@setupPriceField
            firstItem.price = value
            firstItem.rawPriceExpression = raw
            updateTotal()
        }
        PriceCalculator.setupPriceField(d.etPaid, d.tilPaid, { rawPaid }) { value, raw, _ ->
            paid = value
            rawPaid = raw
        }

        d.btnAddItem.setOnClickListener {
            d.root.findFocus()?.clearFocus()
            extraItems.add(FoodItem())
            renderRows()
            extraRows.lastOrNull()?.etExtraFoodName?.requestFocus()
        }

        d.btnRemoveItem1.setOnClickListener {
            if (extraItems.isEmpty()) return@setOnClickListener
            d.root.findFocus()?.clearFocus()
            val next = extraItems.removeAt(0)
            suppress = true
            try {
                firstItem.name = next.name
                firstItem.price = next.price
                firstItem.rawPriceExpression = next.rawPriceExpression
                d.etFood.setText(next.name, false)
                d.etPrice.setText(PriceCalculator.fieldText(next.price))
                d.tilPrice.clearError()
            } finally {
                suppress = false
            }
            renderRows()
        }

        d.cbPaidInFull.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                paidBeforeFull = d.etPaid.text.toString()
                d.tilPaid.clearError()
                syncPaidInFull()
            } else {
                d.etPaid.setText(paidBeforeFull)
            }
            d.tilPaid.isEnabled = !checked
        }

        d.etName.doAfterTextChanged { s ->
            d.tilName.clearError()
            val text = s?.toString().orEmpty()
            d.tilName.helperText = if (viewModel.hasFriendNamed(text)) {
                getString(R.string.already_in_list, NameFormatter.formatName(text))
            } else null
        }

        fun reset() {
            suppress = true
            try {
                d.cbPaidInFull.isChecked = false
                firstItem.name = ""
                firstItem.price = 0.0
                firstItem.rawPriceExpression = null
                extraItems.clear()
                d.etFood.setText("", false)
                d.etPrice.setText("")
                d.etPaid.setText("")
                paid = 0.0
                rawPaid = null
                paidBeforeFull = ""
                listOf(d.tilPrice, d.tilPaid).forEach { it.clearError() }
            } finally {
                suppress = false
            }
            renderRows()
            d.etName.setText("", false)
            d.etName.requestFocus()
        }

        /** Validates and adds the friend. Returns the added name, or null if something needs fixing. */
        fun tryAdd(): String? {
            val name = NameFormatter.formatName(d.etName.text.toString())
            if (name.isBlank()) {
                d.tilName.error = getString(R.string.name_required)
                d.etName.requestFocus()
                return null
            }
            val priceFields = buildList {
                add(d.etPrice to d.tilPrice)
                extraRows.forEach { add(it.etExtraFoodPrice to it.tilExtraFoodPrice) }
                add(d.etPaid to d.tilPaid)
            }
            for ((field, til) in priceFields) {
                if (PriceCalculator.evaluate(field.text.toString()).isFailure) {
                    til.error = getString(R.string.invalid_calculation)
                    field.requestFocus()
                    return null
                }
            }

            val items = (listOf(firstItem) + extraItems)
                .filterIndexed { i, item -> i == 0 || item.name.isNotBlank() || !Order.isZero(item.price) }
                .map { it.copy() }
                .toMutableList()
            val order = Order(friendName = name, paid = paid, previousPaid = paid, rawPaidExpression = rawPaid, items = items)
            order.syncItems()
            if (d.cbPaidInFull.isChecked) {
                order.previousPaid = PriceCalculator.evaluate(paidBeforeFull).getOrDefault(0.0)
                order.paid = order.price
                order.rawPaidExpression = order.rawPriceExpression
                order.isDone = true
            }
            pendingScrollToId = order.id
            if (!viewModel.addFriendOrder(order)) {
                pendingScrollToId = null
                return null
            }
            return name
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_friend_title)
            .setView(d.root)
            .setPositiveButton(R.string.add, null)
            .setNeutralButton(R.string.add_and_next, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (tryAdd() != null) dialog.dismiss()
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                val added = tryAdd() ?: return@setOnClickListener
                reset()
                d.tilName.helperText = "${getString(R.string.added_friend, added)} ✓"
            }
        }
        dialog.focusOnShow(d.etName)
        dialog.show()
    }

    // ------------------------------------------------------------------ recent friends

    private fun showQuickAddDialog() {
        val suggestions = viewModel.allUniqueNames.value.orEmpty()
        if (suggestions.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.recent_title)
                .setMessage(R.string.recent_empty)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val d = DialogListBinding.inflate(layoutInflater)
        d.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        val alreadyAdded = viewModel.currentOrders.map { it.friendName.lowercase() }.toSet()

        lateinit var dialog: AlertDialog
        lateinit var suggestionsAdapter: SuggestionsAdapter

        fun refreshControls() {
            val count = suggestionsAdapter.selectedNames.size
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.let { button ->
                button.isEnabled = count > 0
                button.text = if (count > 0) getString(R.string.add_selected_count, count) else getString(R.string.add_selected)
            }
            d.cbSelectAll.isChecked = suggestionsAdapter.allVisibleSelected
            d.tvEmpty.isVisible = suggestionsAdapter.visibleCount == 0
        }

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recent_title)
            .setView(d.root)
            .setPositiveButton(R.string.add_selected) { _, _ ->
                val result = viewModel.addFriendsByName(suggestionsAdapter.selectedNames.toList())
                showAddResult(result)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        suggestionsAdapter = SuggestionsAdapter(
            suggestions,
            alreadyAdded,
            onRemoveClicked = { nameToRemove ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.remove_saved_title, nameToRemove))
                    .setMessage(R.string.remove_saved_body)
                    .setPositiveButton(R.string.remove) { _, _ ->
                        viewModel.removeSuggestion(nameToRemove)
                        val updatedList = viewModel.allUniqueNames.value.orEmpty()
                        if (updatedList.isEmpty()) {
                            dialog.dismiss()
                        } else {
                            suggestionsAdapter.updateData(updatedList)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
            onSelectionChanged = { refreshControls() }
        )
        d.recyclerView.adapter = suggestionsAdapter

        val selectable = suggestions.count { it.lowercase() !in alreadyAdded }
        d.cbSelectAll.isVisible = selectable > 1
        d.cbSelectAll.setOnClickListener { suggestionsAdapter.setAllVisibleSelected(d.cbSelectAll.isChecked) }

        d.tilSearch.isVisible = suggestions.size > 6
        d.etSearch.doAfterTextChanged {
            suggestionsAdapter.filter(it?.toString().orEmpty())
            refreshControls()
        }

        dialog.setOnShowListener { refreshControls() }
        dialog.show()
    }

    private fun showAddResult(result: OrderViewModel.AddResult) {
        if (result.added == 0 && result.skipped == 0) return
        val parts = mutableListOf<String>()
        if (result.added > 0) parts.add(resources.getQuantityString(R.plurals.added_friends, result.added, result.added))
        if (result.skipped > 0) parts.add(resources.getQuantityString(R.plurals.skipped_duplicates, result.skipped, result.skipped))
        snackbar(parts.joinToString(" · "), Snackbar.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ bulk

    private fun showBulkAddDialog() {
        val d = DialogBulkAddBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bulk_title)
            .setView(d.root)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        d.etBulkNames.doAfterTextChanged { s ->
            val count = s?.toString().orEmpty().split("\n").count { NameFormatter.formatName(it).isNotBlank() }
            d.tilBulkNames.clearError()
            d.tilBulkNames.helperText = if (count > 0) resources.getQuantityString(R.plurals.bulk_names_count, count, count) else null
        }

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val names = d.etBulkNames.text.toString()
                if (names.lines().none { NameFormatter.formatName(it).isNotBlank() }) {
                    d.tilBulkNames.error = getString(R.string.bulk_required)
                    return@setOnClickListener
                }
                showAddResult(viewModel.addFriendsBulk(names))
                dialog.dismiss()
            }
        }
        dialog.focusOnShow(d.etBulkNames)
        dialog.show()
    }

    // ------------------------------------------------------------------ history

    private fun showSavedLogsDialog() {
        val logs = viewModel.getSavedLogs()
        if (logs.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_title)
                .setMessage(R.string.history_empty)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val d = DialogListBinding.inflate(layoutInflater)
        d.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_title)
            .setView(d.root)
            .setNegativeButton(R.string.close, null)
            .create()

        d.recyclerView.adapter = HistoryAdapter(logs) { log ->
            dialog.dismiss()
            showLogDetailDialog(log)
        }

        dialog.show()
    }

    private fun showLogDetailDialog(log: String) {
        val d = DialogHistoryDetailBinding.inflate(layoutInflater)
        val entry = LogEntry.parse(log)
        val ctx = requireContext()

        d.tvLogHeader.text = entry.restaurant.ifBlank { getString(R.string.unknown_restaurant) }
        d.tvLogTitle.text = entry.displayDate
        d.tvLogTitle.isVisible = entry.displayDate.isNotBlank()
        d.tvLogDetails.text = ReceiptFormatter.applyColors(
            entry.body,
            ContextCompat.getColor(ctx, R.color.status_due),
            ContextCompat.getColor(ctx, R.color.status_refund)
        )

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(d.root)
            .create()

        d.btnBackLog.setOnClickListener {
            dialog.dismiss()
            showSavedLogsDialog()
        }

        d.btnCloseLog.setOnClickListener { dialog.dismiss() }

        d.btnShareLog.setOnClickListener {
            val text = buildString {
                append("🧾 ").append(d.tvLogHeader.text)
                if (entry.displayDate.isNotBlank()) append("\n").append(entry.displayDate)
                append("\n\n").append(entry.body)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
        }

        d.btnDeleteLog.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.delete_log_title)
                .setMessage(R.string.delete_log_body)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.deleteLog(log)
                    dialog.dismiss()
                    showSavedLogsDialog()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
