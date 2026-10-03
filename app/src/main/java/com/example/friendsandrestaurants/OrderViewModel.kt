package com.example.friendsandrestaurants

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.friendsandrestaurants.data.FoodItem
import com.example.friendsandrestaurants.data.Order
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID

/**
 * Holds the current bill.
 *
 * All mutations happen on the main thread and use `setValue`, so every read straight after a write
 * sees the new list (`postValue` would make back-to-back adds and saves read stale data).
 * Orders are copied before being changed here, so the RecyclerView diff can tell what changed.
 */
class OrderViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("orders_prefs", Context.MODE_PRIVATE)

    /** Always-current list, including live edits that have not been re-sorted/re-emitted yet. */
    private var orderList: List<Order> = emptyList()

    private val _orders = MutableLiveData<List<Order>>(emptyList())
    /** Sorted list for display. Emitted on structural changes and when an edit is committed. */
    val orders: LiveData<List<Order>> = _orders

    private val _summary = MutableLiveData(BillSummary.EMPTY)
    /** Totals, updated on every change including each keystroke. */
    val summary: LiveData<BillSummary> = _summary

    val allUniqueNames = MutableLiveData<List<String>>(emptyList())
    val allUniqueFoodItems = MutableLiveData<List<String>>(emptyList())

    var restaurantName: String = ""
        private set

    val currentOrders: List<Order> get() = orderList

    init {
        loadData()
    }

    // ---------------------------------------------------------------- list plumbing

    private fun commit(list: List<Order>, emit: Boolean = true) {
        orderList = if (emit) sortOrders(list) else list
        saveData()
        _summary.value = BillSummary.of(orderList)
        if (emit) {
            _orders.value = orderList
            updateSuggestions()
        }
    }

    private fun sortOrders(list: List<Order>): List<Order> = ReceiptFormatter.sortForReceipt(list)

    private fun normalize(order: Order): Order {
        val copy = order.deepCopy()
        val formatted = NameFormatter.formatName(copy.friendName)
        if (formatted.isNotBlank()) copy.friendName = formatted
        copy.items.forEach { it.name = it.name.lowercase().trim() }
        copy.syncItems()
        return copy
    }

    private fun updateSuggestions() {
        val names = prefs.getStringSet(KEY_NAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val items = prefs.getStringSet(KEY_ITEMS, emptySet())?.toMutableSet() ?: mutableSetOf()

        orderList.forEach { order ->
            if (order.friendName.isNotBlank()) names.add(order.friendName)
            order.items.forEach { item ->
                val name = suggestionName(item.name)
                if (name.isNotBlank()) items.add(name)
            }
        }
        // Older versions stored joined multi-item strings ("nan, chicken") as one suggestion.
        items.removeAll { it.contains(", ") && it.split(", ").all { part -> part in items } }

        allUniqueNames.value = names.sortedWith(String.CASE_INSENSITIVE_ORDER)
        allUniqueFoodItems.value = items.sortedWith(String.CASE_INSENSITIVE_ORDER)

        prefs.edit()
            .putStringSet(KEY_NAMES, names)
            .putStringSet(KEY_ITEMS, items)
            .apply()
    }

    // ---------------------------------------------------------------- friends

    data class AddResult(val added: Int, val skipped: Int)

    /** Adds plain friends by name, skipping blanks and anyone already on the bill (case-insensitive). */
    fun addFriendsByName(names: List<String>): AddResult {
        val (toAdd, skipped) = dedupeNames(names, orderList.map { it.friendName })
        if (toAdd.isNotEmpty()) {
            commit(orderList + toAdd.map { Order(friendName = it).apply { syncItems() } })
        }
        return AddResult(toAdd.size, skipped)
    }

    fun addFriendsBulk(text: String): AddResult = addFriendsByName(text.split("\n"))

    /** Adds a fully specified order (from the Add friend dialog). */
    fun addFriendOrder(order: Order): Boolean {
        val normalized = normalize(order)
        if (normalized.friendName.isBlank()) return false
        commit(orderList + normalized)
        return true
    }

    fun hasFriendNamed(name: String): Boolean {
        val formatted = NameFormatter.formatName(name)
        return formatted.isNotBlank() && orderList.any { it.friendName.equals(formatted, ignoreCase = true) }
    }

    /**
     * Adds an item to each of the given friends. With [splitBetween] the price is the total for a
     * shared item and each friend gets an equal share (stored as an expression like "900/3").
     */
    fun addFoodToFriends(orderIds: List<String>, food: String, price: Double, rawExpression: String?, splitBetween: Boolean) {
        val ids = orderIds.toSet()
        val count = ids.size
        if (count == 0) return
        val baseName = food.lowercase().trim()
        val itemName = if (splitBetween && count > 1) {
            "$baseName ${getApplication<Application>().getString(R.string.shared_suffix, count)}"
        } else baseName
        val share = if (splitBetween) price / count else price
        val shareRaw = when {
            splitBetween && count > 1 -> "${PriceCalculator.formatResult(price)}/$count"
            else -> rawExpression
        }

        val updated = orderList.map { existing ->
            if (existing.id !in ids) return@map existing
            val order = existing.deepCopy()
            order.syncItems()
            val first = order.items[0]
            if (order.items.size == 1 && first.name.isBlank() && Order.isZero(first.price)) {
                first.name = itemName
                first.price = share
                first.rawPriceExpression = shareRaw
            } else {
                order.items.add(FoodItem(name = itemName, price = share, rawPriceExpression = shareRaw))
            }
            order.syncItems()
            order.reconcilePaidStatus()
            order
        }
        commit(updated)
    }

    /**
     * Stores an edit made in the list. With [notifyList] = false (live typing) the list is saved and
     * totals refresh, but it is not re-sorted, so the card being edited doesn't jump around.
     */
    fun updateOrder(updatedOrder: Order, notifyList: Boolean = true) {
        if (orderList.none { it.id == updatedOrder.id }) return
        val normalized = normalize(updatedOrder)
        commit(orderList.map { if (it.id == normalized.id) normalized else it }, emit = notifyList)
    }

    fun renameOrder(orderId: String, newName: String): Boolean {
        val formatted = NameFormatter.formatName(newName)
        if (formatted.isBlank()) return false
        commit(orderList.map {
            if (it.id == orderId) it.deepCopy().apply { friendName = formatted } else it
        })
        return true
    }

    fun togglePaid(orderId: String) {
        commit(orderList.map {
            if (it.id == orderId) it.deepCopy().apply { togglePaid() } else it
        })
    }

    fun removeOrder(orderId: String): Order? {
        val removed = orderList.firstOrNull { it.id == orderId } ?: return null
        commit(orderList.filter { it.id != orderId })
        return removed
    }

    fun restoreOrder(order: Order) {
        if (orderList.any { it.id == order.id }) return
        commit(orderList + order.deepCopy())
    }

    fun removeSuggestion(name: String) {
        val names = prefs.getStringSet(KEY_NAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (names.remove(name)) {
            prefs.edit().putStringSet(KEY_NAMES, names).apply()
            allUniqueNames.value = names.sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    fun updateRestaurantName(name: String) {
        if (restaurantName == name) return
        restaurantName = name
        prefs.edit().putString(KEY_RESTAURANT, restaurantName).apply()
    }

    /** Clears the bill and returns what was there, so it can be restored with [restoreBill]. */
    fun clearOrders(clearRestaurant: Boolean = false): Pair<List<Order>, String> {
        val previous = orderList to restaurantName
        if (clearRestaurant) updateRestaurantName("")
        commit(emptyList())
        return previous
    }

    fun restoreBill(snapshot: Pair<List<Order>, String>) {
        updateRestaurantName(snapshot.second)
        commit(snapshot.first)
    }

    // ---------------------------------------------------------------- history

    fun getSavedLogs(): List<String> {
        val jsonString = prefs.getString(KEY_LOGS_JSON, null)
        if (jsonString == null) {
            // Migration from old StringSet format
            val oldLogs = prefs.getStringSet("saved_logs", null)
            if (oldLogs != null) {
                val list = oldLogs.toList().sortedDescending()
                val jsonArray = JSONArray()
                list.forEach { jsonArray.put(it) }
                prefs.edit()
                    .putString(KEY_LOGS_JSON, jsonArray.toString())
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
            prefs.edit().putString(KEY_LOGS_JSON, jsonArray.toString()).apply()
        }
    }

    private fun currentSignature(): String = "$restaurantName\n${generateFullReceiptText()}"

    /** True if this exact bill (same restaurant and amounts) is already in history. */
    fun isCurrentSessionSaved(): Boolean =
        orderList.isNotEmpty() && prefs.getString(KEY_LAST_SAVED, null) == currentSignature()

    fun saveSessionLog() {
        val receiptText = generateFullReceiptText()
        val currentLogs = getSavedLogs().toMutableList()
        val timestamp = ReceiptFormatter.storageTimestamp()
        val restaurantInfo = restaurantName.ifBlank { getApplication<Application>().getString(R.string.unknown_restaurant) }

        currentLogs.add("[$timestamp] $restaurantInfo\n$receiptText")

        val jsonArray = JSONArray()
        currentLogs.forEach { jsonArray.put(it) }
        prefs.edit()
            .putString(KEY_LOGS_JSON, jsonArray.toString())
            .putString(KEY_LAST_SAVED, currentSignature())
            .apply()
    }

    fun generateFullReceiptText(): String = ReceiptFormatter.buildTable(orderList)

    fun generateShareText(): String = ReceiptFormatter.buildShareText(restaurantName, orderList, Date())

    // ---------------------------------------------------------------- persistence

    private fun saveData() {
        val jsonArray = JSONArray()
        try {
            for (order in orderList) {
                val jsonObject = JSONObject()
                jsonObject.put("id", order.id)
                jsonObject.put("friendName", order.friendName)
                jsonObject.put("foodItem", order.foodItem)
                jsonObject.put("price", order.price)
                jsonObject.put("paid", order.paid)
                jsonObject.put("previousPaid", order.previousPaid)
                jsonObject.put("isDone", order.isDone)
                if (order.rawPriceExpression != null) {
                    jsonObject.put("rawPriceExpression", order.rawPriceExpression)
                }
                if (order.rawPaidExpression != null) {
                    jsonObject.put("rawPaidExpression", order.rawPaidExpression)
                }

                val itemsArray = JSONArray()
                for (item in order.items) {
                    val itemObj = JSONObject()
                    itemObj.put("id", item.id)
                    itemObj.put("name", item.name)
                    itemObj.put("price", item.price)
                    if (item.rawPriceExpression != null) {
                        itemObj.put("rawPriceExpression", item.rawPriceExpression)
                    }
                    itemsArray.put(itemObj)
                }
                jsonObject.put("items", itemsArray)

                jsonArray.put(jsonObject)
            }
            prefs.edit()
                .putString(KEY_ORDERS, jsonArray.toString())
                .putString(KEY_RESTAURANT, restaurantName)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadData() {
        restaurantName = prefs.getString(KEY_RESTAURANT, "") ?: ""
        allUniqueNames.value = prefs.getStringSet(KEY_NAMES, emptySet())?.sortedWith(String.CASE_INSENSITIVE_ORDER) ?: emptyList()
        allUniqueFoodItems.value = prefs.getStringSet(KEY_ITEMS, emptySet())?.sortedWith(String.CASE_INSENSITIVE_ORDER) ?: emptyList()

        val list = mutableListOf<Order>()
        try {
            val jsonString = prefs.getString(KEY_ORDERS, null)
            if (jsonString != null) {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val order = Order(
                        id = jsonObject.optString("id", UUID.randomUUID().toString()),
                        friendName = jsonObject.optString("friendName", "Unknown"),
                        foodItem = jsonObject.optString("foodItem", ""),
                        price = jsonObject.optDouble("price", 0.0),
                        paid = jsonObject.optDouble("paid", 0.0),
                        previousPaid = jsonObject.optDouble("previousPaid", 0.0),
                        isDone = jsonObject.optBoolean("isDone", false),
                        rawPriceExpression = jsonObject.optStringOrNull("rawPriceExpression"),
                        rawPaidExpression = jsonObject.optStringOrNull("rawPaidExpression")
                    )

                    val itemsArray = jsonObject.optJSONArray("items")
                    if (itemsArray != null) {
                        for (j in 0 until itemsArray.length()) {
                            val itemObj = itemsArray.getJSONObject(j)
                            order.items.add(FoodItem(
                                id = itemObj.optString("id", UUID.randomUUID().toString()),
                                name = itemObj.optString("name", ""),
                                price = itemObj.optDouble("price", 0.0),
                                rawPriceExpression = itemObj.optStringOrNull("rawPriceExpression")
                            ))
                        }
                    }
                    order.syncItems()
                    list.add(order)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        orderList = sortOrders(list)
        _orders.value = orderList
        _summary.value = BillSummary.of(orderList)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    companion object {
        private const val KEY_ORDERS = "orders_json"
        private const val KEY_RESTAURANT = "restaurant_name"
        private const val KEY_NAMES = "all_names"
        private const val KEY_ITEMS = "all_items"
        private const val KEY_LOGS_JSON = "saved_logs_json"
        private const val KEY_LAST_SAVED = "last_saved_signature"

        private val SHARED_SUFFIX = Regex("""\s*\(shared ÷\d+\)$""")

        /** Strips the "(shared ÷3)" marker so suggestions show the plain item name. */
        fun suggestionName(itemName: String): String = itemName.replace(SHARED_SUFFIX, "").trim().lowercase()

        /**
         * Formats names and drops blanks plus anything already present (case-insensitive),
         * including repeats within [names] itself. Returns the names to add and how many were skipped.
         */
        fun dedupeNames(names: List<String>, existing: List<String>): Pair<List<String>, Int> {
            val seen = existing.map { it.lowercase() }.toMutableSet()
            val toAdd = mutableListOf<String>()
            var skipped = 0
            for (raw in names) {
                val formatted = NameFormatter.formatName(raw)
                if (formatted.isBlank()) continue
                if (seen.add(formatted.lowercase())) toAdd.add(formatted) else skipped++
            }
            return toAdd to skipped
        }
    }
}
