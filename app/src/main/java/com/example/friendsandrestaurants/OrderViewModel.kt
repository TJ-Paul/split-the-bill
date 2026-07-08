package com.example.friendsandrestaurants

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.friendsandrestaurants.data.Order
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class OrderViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("orders_prefs", Context.MODE_PRIVATE)
    
    private val _orders = MutableLiveData<List<Order>>(emptyList())
    val orders: LiveData<List<Order>> = _orders

    val allUniqueNames = MutableLiveData<Set<String>>(emptySet())
    val allUniqueFoodItems = MutableLiveData<Set<String>>(emptySet())

    var restaurantName: String = ""

    init {
        loadData()
    }

    private fun updateSuggestions() {
        val currentOrders = _orders.value ?: emptyList()
        val names = prefs.getStringSet("all_names", emptySet())?.toMutableSet() ?: mutableSetOf()
        val items = prefs.getStringSet("all_items", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        currentOrders.forEach {
            if (it.friendName.isNotBlank()) names.add(it.friendName)
            if (it.foodItem.isNotBlank()) items.add(it.foodItem)
        }
        
        allUniqueNames.value = names
        allUniqueFoodItems.value = items
        
        prefs.edit()
            .putStringSet("all_names", names)
            .putStringSet("all_items", items)
            .apply()
    }

    fun addFriend(name: String, food: String = "", price: Double = 0.0, paid: Double = 0.0) {
        val currentList = _orders.value ?: emptyList()
        val formattedName = formatName(name)
        if (formattedName.isNotBlank()) {
            val newOrder = Order(
                friendName = formattedName,
                foodItem = food.lowercase().trim(),
                price = price,
                paid = paid,
                previousPaid = paid
            )
            _orders.value = currentList + newOrder
            saveData()
            updateSuggestions()
        }
    }

    fun addFriendsBulk(names: String) {
        val nameList = names.split("\n")
            .map { formatName(it) }
            .filter { it.isNotBlank() }
        
        val currentList = _orders.value ?: emptyList()
        val newOrders = nameList.map { Order(friendName = it) }
        _orders.value = currentList + newOrders
        saveData()
        updateSuggestions()
    }

    fun updateOrder(updatedOrder: Order) {
        updatedOrder.foodItem = updatedOrder.foodItem.lowercase().trim()
        val currentList = _orders.value ?: emptyList()
        _orders.value = currentList.map {
            if (it.id == updatedOrder.id) updatedOrder else it
        }
        saveData()
    }

    fun removeOrder(order: Order) {
        val currentList = _orders.value ?: emptyList()
        _orders.value = currentList.filter { it.id != order.id }
        saveData()
    }

    fun removeSuggestion(name: String) {
        val names = prefs.getStringSet("all_names", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (names.remove(name)) {
            prefs.edit().putStringSet("all_names", names).apply()
            allUniqueNames.value = names
        }
    }

    fun updateRestaurantName(name: String) {
        restaurantName = name
        prefs.edit().putString("restaurant_name", restaurantName).apply()
    }

    fun clearOrders() {
        _orders.value = emptyList()
        saveData()
    }

    fun getSavedLogs(): List<String> {
        val jsonString = prefs.getString("saved_logs_json", null)
        if (jsonString == null) {
            // Migration from old StringSet format
            val oldLogs = prefs.getStringSet("saved_logs", null)
            if (oldLogs != null) {
                val list = oldLogs.toList().sortedDescending()
                val jsonArray = JSONArray()
                list.forEach { jsonArray.put(it) }
                prefs.edit()
                    .putString("saved_logs_json", jsonArray.toString())
                    .remove("saved_logs")
                    .apply()
                return list
            }
            return emptyList()
        }

        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            list.sortedDescending()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteLog(log: String) {
        val currentLogs = getSavedLogs().toMutableList()
        if (currentLogs.remove(log)) {
            val jsonArray = JSONArray()
            currentLogs.forEach { jsonArray.put(it) }
            prefs.edit().putString("saved_logs_json", jsonArray.toString()).apply()
        }
    }

    fun saveSessionLog() {
        val receiptText = generateFullReceiptText()
        val currentLogs = getSavedLogs().toMutableList()
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        val restaurantInfo = if (restaurantName.isBlank()) "Unknown Restaurant" else restaurantName
        
        val logEntry = "[$timestamp] $restaurantInfo\n$receiptText"
        currentLogs.add(logEntry)
        
        val jsonArray = JSONArray()
        currentLogs.forEach { jsonArray.put(it) }
        prefs.edit().putString("saved_logs_json", jsonArray.toString()).apply()
    }

    fun generateFullReceiptText(): String {
        val ordersList = _orders.value ?: return "No orders found."
        if (ordersList.isEmpty()) return "No friends or orders added yet."
        
        val sb = StringBuilder()
        
        sb.append(String.format(Locale.getDefault(), "%-14s | %-14s\n", "NAME", "ITEM"))
        sb.append(String.format(Locale.getDefault(), "%-14s | %-14s\n", "PRICE", "PAID"))
        sb.append("=".repeat(31)).append("\n\n")
        
        var totalBill = 0.0
        var totalPaid = 0.0
        
        for (order in ordersList) {
            sb.append(String.format(Locale.getDefault(), "%-14s | %-14s\n", 
                order.friendName.take(14),
                order.foodItem.take(14)
            ))
            sb.append(String.format(Locale.getDefault(), "%-14d | %-14d\n", 
                order.price.toInt(),
                order.paid.toInt()
            ))
            
            val cb = order.cashback
            if (cb != 0.0) {
                val label = if (cb > 0) "REFUND" else "DUE"
                sb.append(String.format(Locale.getDefault(), "   └ %s: %d tk\n", label, abs(cb).toInt()))
            }
            sb.append("-".repeat(31)).append("\n")
            
            totalBill += order.price
            totalPaid += order.paid
        }
        
        sb.append("\n")
        sb.append(String.format(Locale.getDefault(), "TOTAL BILL:      %d tk\n", totalBill.toInt()))
        sb.append(String.format(Locale.getDefault(), "TOTAL PAID:      %d tk\n", totalPaid.toInt()))
        
        val netChange = totalPaid - totalBill
        sb.append("=".repeat(31)).append("\n")
        if (netChange > 0) {
            sb.append(String.format(Locale.getDefault(), "OVERALL REFUND:  %d tk 💰", netChange.toInt()))
        } else if (netChange < 0) {
            sb.append(String.format(Locale.getDefault(), "OVERALL DUE:     %d tk ⚠️", abs(netChange).toInt()))
        } else {
            sb.append("STATUS:          ALL SETTLED ✅")
        }

        return sb.toString()
    }

    private fun formatName(name: String): String {
        if (name.isBlank()) return ""
        return name.trim().lowercase().split(" ").filter { it.isNotBlank() }.joinToString(" ") { 
            it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
        }
    }

    private fun saveData() {
        val list = _orders.value ?: return
        val jsonArray = JSONArray()
        try {
            for (order in list) {
                val jsonObject = JSONObject()
                jsonObject.put("id", order.id)
                jsonObject.put("friendName", order.friendName)
                jsonObject.put("foodItem", order.foodItem)
                jsonObject.put("price", order.price)
                jsonObject.put("paid", order.paid)
                jsonObject.put("previousPaid", order.previousPaid)
                jsonObject.put("isDone", order.isDone)
                jsonArray.put(jsonObject)
            }
            prefs.edit()
                .putString("orders_json", jsonArray.toString())
                .putString("restaurant_name", restaurantName)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadData() {
        try {
            restaurantName = prefs.getString("restaurant_name", "") ?: ""
            allUniqueNames.value = prefs.getStringSet("all_names", emptySet())?.toSet() ?: emptySet()
            allUniqueFoodItems.value = prefs.getStringSet("all_items", emptySet())?.toSet() ?: emptySet()

            val jsonString = prefs.getString("orders_json", null)
            if (jsonString != null) {
                val jsonArray = JSONArray(jsonString)
                val list = mutableListOf<Order>()
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    list.add(Order(
                        id = jsonObject.optString("id", java.util.UUID.randomUUID().toString()),
                        friendName = jsonObject.optString("friendName", "Unknown"),
                        foodItem = jsonObject.optString("foodItem", ""),
                        price = jsonObject.optDouble("price", 0.0),
                        paid = jsonObject.optDouble("paid", 0.0),
                        previousPaid = jsonObject.optDouble("previousPaid", 0.0),
                        isDone = jsonObject.optBoolean("isDone", false)
                    ))
                }
                _orders.value = list
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _orders.value = emptyList()
        }
    }
}