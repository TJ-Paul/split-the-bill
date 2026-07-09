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

    val allUniqueNames = MutableLiveData<List<String>>(emptyList())
    val allUniqueFoodItems = MutableLiveData<List<String>>(emptyList())

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
        
        allUniqueNames.value = names.sorted()
        allUniqueFoodItems.value = items.sorted()
        
        prefs.edit()
            .putStringSet("all_names", names)
            .putStringSet("all_items", items)
            .apply()
    }

    private fun sortOrders(list: List<Order>): List<Order> {
        return list.sortedWith(compareBy<Order> {
            when {
                it.cashback < 0 -> 0 // Less paid
                it.cashback > 0 -> 1 // Over paid
                else -> 2            // Settled
            }
        }.thenBy { it.friendName.lowercase() })
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
            _orders.value = sortOrders(currentList + newOrder)
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
        _orders.value = sortOrders(currentList + newOrders)
        saveData()
        updateSuggestions()
    }

    fun addFoodToFriends(names: List<String>, food: String, price: Double) {
        val currentList = (_orders.value ?: emptyList()).toMutableList()
        val foodFormatted = food.lowercase().trim()
        
        names.forEach { name ->
            val index = currentList.indexOfFirst { it.friendName == name }
            if (index != -1) {
                val existing = currentList[index]
                val newFood = if (existing.foodItem.isBlank()) foodFormatted else "${existing.foodItem}, $foodFormatted"
                currentList[index] = existing.copy(
                    foodItem = newFood,
                    price = existing.price + price
                )
            } else {
                currentList.add(Order(
                    friendName = name,
                    foodItem = foodFormatted,
                    price = price
                ))
            }
        }
        _orders.value = sortOrders(currentList)
        saveData()
        updateSuggestions()
    }

    fun updateOrder(updatedOrder: Order) {
        updatedOrder.foodItem = updatedOrder.foodItem.lowercase().trim()
        val currentList = _orders.value ?: emptyList()
        val newList = currentList.map {
            if (it.id == updatedOrder.id) updatedOrder else it
        }
        _orders.value = sortOrders(newList)
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
            allUniqueNames.value = names.sorted()
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
        val rawOrders = _orders.value ?: return "No orders found."
        if (rawOrders.isEmpty()) return "No friends or orders added yet."
        
        val ordersList = sortOrders(rawOrders)
        
        // Dynamic column width calculation based on content
        var maxNameLen = "FRIEND".length
        var maxItemLen = "ITEM & INFO".length
        
        ordersList.forEach {
            maxNameLen = maxOf(maxNameLen, it.friendName.length)
            maxItemLen = maxOf(maxItemLen, it.foodItem.length)
            val pricePaidStr = "${it.paid.toInt()} / ${it.price.toInt()} tk"
            maxItemLen = maxOf(maxItemLen, pricePaidStr.length)
            val cb = it.cashback
            if (cb != 0.0) {
                val label = if (cb > 0) "REFUND" else "DUE"
                maxItemLen = maxOf(maxItemLen, "$label: ${abs(cb).toInt()} tk".length)
            }
        }
        
        // Add relative padding and boundaries
        val c1W = (maxNameLen + 6).coerceIn(12, 20)
        val c2W = (maxItemLen + 6).coerceIn(16, 28)
        
        val sb = StringBuilder()
        
        fun line(c: String = "-") = "+${c.repeat(c1W)}+${c.repeat(c2W)}+\n"

        fun wrap(text: String, width: Int): List<String> {
            if (text.isEmpty()) return listOf("")
            val contentWidth = (width - 2).coerceAtLeast(1)
            return text.chunked(contentWidth)
        }

        fun row(s1: String, s2: String): String {
            val lines1 = wrap(s1, c1W)
            val lines2 = wrap(s2, c2W)
            val maxLines = maxOf(lines1.size, lines2.size)
            val res = StringBuilder()
            for (i in 0 until maxLines) {
                val p1 = center(lines1.getOrElse(i) { "" }, c1W)
                val p2 = center(lines2.getOrElse(i) { "" }, c2W)
                res.append("|$p1|$p2|\n")
            }
            return res.toString()
        }
        
        sb.append(line())
        sb.append(row("FRIEND", "ITEM & INFO"))
        sb.append(line("="))
        
        var totalBill = 0.0
        var totalPaid = 0.0
        
        for (order in ordersList) {
            sb.append(row(order.friendName, order.foodItem))
            sb.append(row("", "${order.paid.toInt()} / ${order.price.toInt()} tk"))
            
            val cb = order.cashback
            if (cb != 0.0) {
                val label = if (cb > 0) "REFUND" else "DUE"
                sb.append(row("", "$label: ${abs(cb).toInt()} tk"))
            }
            sb.append(line())
            
            totalBill += order.price
            totalPaid += order.paid
        }
        
        sb.append("\n")
        val totalWidth = c1W + c2W + 3
        
        fun footerRow(label: String, value: String): String {
            val space = totalWidth - label.length - value.length
            return label + " ".repeat(maxOf(1, space)) + value + "\n"
        }
        
        sb.append(footerRow("TOTAL BILL:", "${totalBill.toInt()} tk"))
        sb.append(footerRow("TOTAL PAID:", "${totalPaid.toInt()} tk"))
        sb.append("-".repeat(totalWidth)).append("\n")
        
        val netChange = totalPaid - totalBill
        if (netChange > 0) {
            sb.append(String.format(Locale.getDefault(), "OVERALL REFUND: %d tk 💰", netChange.toInt()))
        } else if (netChange < 0) {
            sb.append(String.format(Locale.getDefault(), "OVERALL DUE:    %d tk ⚠️", abs(netChange).toInt()))
        } else {
            sb.append("STATUS:         ALL SETTLED ✅")
        }

        return sb.toString()
    }

    private fun center(text: String, width: Int): String {
        val padding = width - text.length
        val left = padding / 2
        val right = padding - left
        return " ".repeat(maxOf(0, left)) + text + " ".repeat(maxOf(0, right))
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
            allUniqueNames.value = prefs.getStringSet("all_names", emptySet())?.sorted() ?: emptyList()
            allUniqueFoodItems.value = prefs.getStringSet("all_items", emptySet())?.sorted() ?: emptyList()

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
                _orders.value = sortOrders(list)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _orders.value = emptyList()
        }
    }
}