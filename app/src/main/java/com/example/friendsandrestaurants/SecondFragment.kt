package com.example.friendsandrestaurants

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.friendsandrestaurants.databinding.FragmentSecondBinding
import java.util.Locale

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

        binding.tvRestaurantName.text = viewModel.restaurantName.ifBlank { "Unspecified Restaurant" }
        
        setupReceipt()

        binding.btnSaveLog.setOnClickListener {
            viewModel.saveSessionLog()
            Toast.makeText(requireContext(), "Log saved successfully!", Toast.LENGTH_SHORT).show()
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupReceipt() {
        val orders = viewModel.orders.value ?: emptyList()
        if (orders.isEmpty()) {
            binding.tvReceiptDetails.text = "No friends or orders added yet."
            return
        }
        
        val baseText = viewModel.generateFullReceiptText()
        binding.tvReceiptDetails.text = applyReceiptColors(baseText)
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
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (line.contains("REFUND") || line.contains("OVERALL REFUND") || line.contains("ALL SETTLED")) {
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#4CAF50")),
                    currentPos,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
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