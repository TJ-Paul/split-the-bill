package com.example.friendsandrestaurants

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import com.example.friendsandrestaurants.PriceCalculator.formatMoneyWithUnit
import com.example.friendsandrestaurants.data.Order
import com.example.friendsandrestaurants.databinding.FragmentSecondBinding
import com.example.friendsandrestaurants.databinding.ItemReceiptRowBinding
import com.google.android.material.snackbar.Snackbar
import java.util.Date

class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OrderViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_receipt, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == R.id.action_copy) {
                    copyToClipboard()
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        setupReceipt()

        binding.toggleView.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showTable(checkedId == R.id.btnViewTable)
        }

        binding.btnSaveLog.setOnClickListener {
            viewModel.saveSessionLog()
            updateSaveButton()
            Snackbar.make(binding.coordinator, R.string.saved_snackbar, Snackbar.LENGTH_SHORT)
                .setAnchorView(binding.bottomBar)
                .show()
        }

        binding.btnShare.setOnClickListener { shareReceipt() }
    }

    private fun setupReceipt() {
        val orders = viewModel.currentOrders
        val summary = BillSummary.of(orders)
        val ctx = requireContext()

        binding.tvRestaurantName.text = viewModel.restaurantName.ifBlank { getString(R.string.unspecified_restaurant) }
        val friendsText = resources.getQuantityString(R.plurals.friend_count, summary.friendCount, summary.friendCount)
        binding.tvReceiptMeta.text = getString(R.string.receipt_meta, ReceiptFormatter.prettyDate(Date()), friendsText)
        binding.tvTotalBill.text = formatMoneyWithUnit(summary.totalBill)
        binding.tvStatPaid.text = formatMoneyWithUnit(summary.totalPaid)
        binding.tvStatDue.text = formatMoneyWithUnit(summary.totalDue)
        binding.tvStatRefund.text = formatMoneyWithUnit(summary.totalRefund)

        val net = summary.net
        val (overallText, overallColor, overallBg) = when {
            net < 0 -> Triple(getString(R.string.overall_due, formatMoneyWithUnit(-net)), R.color.status_due, R.drawable.bg_pill_due)
            net > 0 -> Triple(getString(R.string.overall_refund, formatMoneyWithUnit(net)), R.color.status_refund, R.drawable.bg_pill_success)
            else -> Triple(getString(R.string.summary_all_settled), R.color.status_refund, R.drawable.bg_pill_success)
        }
        binding.tvOverall.text = overallText
        binding.tvOverall.setTextColor(ContextCompat.getColor(ctx, overallColor))
        binding.tvOverall.setBackgroundResource(overallBg)

        val isEmpty = orders.isEmpty()
        binding.tvEmptyReceipt.isVisible = isEmpty
        binding.toggleView.isVisible = !isEmpty
        binding.tvOverall.isVisible = !isEmpty
        binding.btnShare.isEnabled = !isEmpty

        binding.tvReceiptDetails.text = ReceiptFormatter.applyColors(
            viewModel.generateFullReceiptText(),
            ContextCompat.getColor(ctx, R.color.status_due),
            ContextCompat.getColor(ctx, R.color.status_refund)
        )

        binding.llReceiptRows.removeAllViews()
        ReceiptFormatter.sortForReceipt(orders).forEach { addReceiptRow(it) }

        showTable(binding.toggleView.checkedButtonId == R.id.btnViewTable)
        updateSaveButton()
    }

    private fun addReceiptRow(order: Order) {
        val ctx = requireContext()
        val row = ItemReceiptRowBinding.inflate(layoutInflater, binding.llReceiptRows, false)
        row.tvName.text = order.friendName
        row.tvAvatar.text = OrderAdapter.initials(order.friendName)
        row.tvAvatar.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, OrderAdapter.avatarColorRes(order.friendName))
        )

        val items = order.items.filter { it.name.isNotBlank() || !Order.isZero(it.price) }
        row.tvItems.text = items.joinToString(" · ") { item ->
            val name = item.name.ifBlank { "—" }
            "$name ${PriceCalculator.formatMoney(item.price)}"
        }
        row.tvItems.isVisible = items.isNotEmpty()
        row.tvPaid.text = getString(
            R.string.receipt_paid_of,
            PriceCalculator.formatMoney(order.paid),
            formatMoneyWithUnit(order.price)
        )

        val cb = order.cashback
        val (text, color, bg) = when {
            cb < 0 -> Triple(getString(R.string.status_due, formatMoneyWithUnit(-cb)), R.color.status_due, R.drawable.bg_pill_due)
            cb > 0 -> Triple(getString(R.string.status_refund, formatMoneyWithUnit(cb)), R.color.status_refund, R.drawable.bg_pill_success)
            else -> Triple(getString(R.string.status_settled), R.color.status_settled, R.drawable.bg_pill_settled)
        }
        row.tvStatus.text = text
        row.tvStatus.setTextColor(ContextCompat.getColor(ctx, color))
        row.tvStatus.setBackgroundResource(bg)

        binding.llReceiptRows.addView(row.root)
    }

    private fun showTable(table: Boolean) {
        val isEmpty = viewModel.currentOrders.isEmpty()
        binding.cardReceipt.isVisible = table && !isEmpty
        binding.cardSummary.isVisible = !table && !isEmpty
    }

    private fun updateSaveButton() {
        val saved = viewModel.isCurrentSessionSaved()
        binding.btnSaveLog.isEnabled = !saved && viewModel.currentOrders.isNotEmpty()
        binding.btnSaveLog.setText(if (saved) R.string.saved_to_history else R.string.save_to_history)
        binding.btnSaveLog.setIconResource(if (saved) R.drawable.ic_check else R.drawable.ic_bookmark_add)
    }

    private fun shareReceipt() {
        val text = viewModel.generateShareText()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, viewModel.restaurantName.ifBlank { getString(R.string.receipt_title) })
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    private fun copyToClipboard() {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.receipt_clipboard_label), viewModel.generateShareText()))
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Snackbar.make(binding.coordinator, R.string.copied, Snackbar.LENGTH_SHORT)
                .setAnchorView(binding.bottomBar)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
