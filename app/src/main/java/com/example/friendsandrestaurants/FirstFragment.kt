package com.example.friendsandrestaurants

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.friendsandrestaurants.databinding.FragmentFirstBinding
import com.example.friendsandrestaurants.databinding.DialogAddFriendFlowBinding
import com.example.friendsandrestaurants.databinding.DialogHistoryDetailBinding
import com.example.friendsandrestaurants.databinding.DialogBulkAddBinding
import com.example.friendsandrestaurants.databinding.DialogListBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: OrderViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = OrderAdapter(
            onOrderUpdated = { order -> viewModel.updateOrder(order) },
            onOrderRemoved = { order -> showRemoveOrderConfirmation(order) }
        )
        
        binding.rvOrders.layoutManager = LinearLayoutManager(context)
        binding.rvOrders.adapter = adapter

        viewModel.orders.observe(viewLifecycleOwner) { orders ->
            adapter.submitList(orders.toList())
            binding.emptyState.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.allUniqueFoodItems.observe(viewLifecycleOwner) { items ->
            adapter.updateSuggestions(items.toList())
        }

        binding.etRestaurant.setText(viewModel.restaurantName)
        binding.etRestaurant.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.updateRestaurantName(s.toString())
            }
        })

        binding.btnAddFriend.setOnClickListener {
            showAddFriendFlowDialog()
        }

        binding.btnQuickAdd.setOnClickListener {
            showQuickAddDialog()
        }

        binding.btnBulkAdd.setOnClickListener {
            showBulkAddDialog()
        }

        binding.btnHistory.setOnClickListener {
            showSavedLogsDialog()
        }

        binding.btnClear.setOnClickListener {
            showWipeConfirmationDialog()
        }
    }

    private fun showWipeConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Wipe List Clean? 🧹")
            .setMessage("This will remove all current friends and their items. This cannot be undone.")
            .setPositiveButton("Wipe Everything") { _, _ ->
                viewModel.clearOrders()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoveOrderConfirmation(order: com.example.friendsandrestaurants.data.Order) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove ${order.friendName}?")
            .setMessage("Are you sure you want to remove this friend's order?")
            .setPositiveButton("Remove") { _, _ ->
                viewModel.removeOrder(order)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddFriendFlowDialog() {
        val dialogBinding = DialogAddFriendFlowBinding.inflate(layoutInflater)
        
        val nameSuggestions = viewModel.allUniqueNames.value?.toList() ?: emptyList()
        val nameAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, nameSuggestions)
        dialogBinding.etName.setAdapter(nameAdapter)

        val foodSuggestions = viewModel.allUniqueFoodItems.value?.toList() ?: emptyList()
        val foodAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, foodSuggestions)
        dialogBinding.etFood.setAdapter(foodAdapter)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnAdd.setOnClickListener {
            val name = dialogBinding.etName.text.toString()
            val food = dialogBinding.etFood.text.toString()
            val price = dialogBinding.etPrice.text.toString().toDoubleOrNull() ?: 0.0
            val paid = dialogBinding.etPaid.text.toString().toDoubleOrNull() ?: 0.0
            
            if (name.isNotBlank()) {
                viewModel.addFriend(name, food, price, paid)
                dialog.dismiss()
            } else {
                dialogBinding.etName.error = "Name required"
            }
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun showQuickAddDialog() {
        val suggestions = viewModel.allUniqueNames.value?.toList() ?: emptyList()
        if (suggestions.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Quick Add")
                .setMessage("No previous friends found. Add friends manually first!")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val dialogListBinding = DialogListBinding.inflate(layoutInflater)
        val rv = dialogListBinding.recyclerView
        rv.layoutManager = LinearLayoutManager(requireContext())
        
        lateinit var suggestionsAdapter: SuggestionsAdapter
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Quick Add Friends")
            .setView(dialogListBinding.root)
            .setPositiveButton("Add Selected") { _, _ ->
                suggestionsAdapter.selectedNames.forEach { name ->
                    viewModel.addFriend(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        suggestionsAdapter = SuggestionsAdapter(suggestions) { nameToRemove ->
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Remove $nameToRemove?")
                .setMessage("This will remove them from suggestions.")
                .setPositiveButton("Remove") { _, _ ->
                    viewModel.removeSuggestion(nameToRemove)
                    val updatedList = viewModel.allUniqueNames.value?.toList() ?: emptyList()
                    if (updatedList.isEmpty()) {
                        dialog.dismiss()
                    } else {
                        suggestionsAdapter.updateData(updatedList)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        rv.adapter = suggestionsAdapter
        dialog.show()
    }

    private fun showBulkAddDialog() {
        val dialogBinding = DialogBulkAddBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnAdd.setOnClickListener {
            val names = dialogBinding.etBulkNames.text.toString()
            if (names.isNotBlank()) {
                viewModel.addFriendsBulk(names)
                dialog.dismiss()
            } else {
                dialogBinding.etBulkNames.error = "Please enter some names"
            }
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showSavedLogsDialog() {
        val logs = viewModel.getSavedLogs()
        if (logs.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("History 📜")
                .setMessage("No logs saved yet. Go to Receipt and click 'Save Copy' to save a log.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val dialogListBinding = DialogListBinding.inflate(layoutInflater)
        val rv = dialogListBinding.recyclerView
        rv.layoutManager = LinearLayoutManager(requireContext())
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("History 📜")
            .setView(dialogListBinding.root)
            .setNegativeButton("Close", null)
            .create()
            
        rv.adapter = HistoryAdapter(logs) { log ->
            dialog.dismiss()
            showLogDetailDialog(log)
        }
        
        dialog.show()
    }

    private fun showLogDetailDialog(log: String) {
        val detailBinding = DialogHistoryDetailBinding.inflate(LayoutInflater.from(requireContext()))
        
        val title = log.substringBefore("\n")
        val details = log.substringAfter("\n")
        
        detailBinding.tvLogTitle.text = title
        detailBinding.tvLogDetails.text = applyReceiptColors(details)
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(detailBinding.root)
            .create()
            
        detailBinding.btnBackLog.setOnClickListener {
            dialog.dismiss()
            showSavedLogsDialog()
        }
            
        detailBinding.btnCloseLog.setOnClickListener {
            dialog.dismiss()
        }
        
        detailBinding.btnDeleteLog.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete this log?")
                .setMessage("This action cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.deleteLog(log)
                    dialog.dismiss()
                    showSavedLogsDialog()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        dialog.show()
    }

    private fun applyReceiptColors(text: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(text)
        val lines = text.split("\n")
        var currentPos = 0
        
        for (line in lines) {
            if (currentPos >= ssb.length) break
            val lineEnd = (currentPos + line.length).coerceAtMost(ssb.length)
            
            if (line.contains("DUE")) {
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#F44336")),
                    currentPos,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (line.contains("REFUND") || line.contains("OVERALL REFUND") || line.contains("ALL SETTLED")) {
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#4CAF50")),
                    currentPos,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            
            currentPos += line.length + 1
        }
        return ssb
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}