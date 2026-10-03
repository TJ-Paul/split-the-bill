package com.example.friendsandrestaurants

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.example.friendsandrestaurants.data.Order

/**
 * Screen-facing view of the bill. The data itself lives in the process-wide [BillRepository], which
 * the bill-sharing web server also edits, so guests' changes show up here live.
 */
class OrderViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = BillRepository.get(application)

    val orders: LiveData<List<Order>> get() = repo.orders
    val summary: LiveData<BillSummary> get() = repo.summary
    val allUniqueNames: LiveData<List<String>> get() = repo.allUniqueNames
    val allUniqueFoodItems: LiveData<List<String>> get() = repo.allUniqueFoodItems

    val restaurantName: String get() = repo.restaurantName
    val currentOrders: List<Order> get() = repo.currentOrders

    fun addFriendsByName(names: List<String>) = repo.addFriendsByName(names)
    fun addFriendsBulk(text: String) = repo.addFriendsBulk(text)
    fun addFriendOrder(order: Order) = repo.addFriendOrder(order)
    fun hasFriendNamed(name: String) = repo.hasFriendNamed(name)

    fun addFoodToFriends(orderIds: List<String>, food: String, price: Double, rawExpression: String?, splitBetween: Boolean) =
        repo.addFoodToFriends(orderIds, food, price, rawExpression, splitBetween)

    fun updateOrder(updatedOrder: Order, notifyList: Boolean = true) = repo.updateOrder(updatedOrder, notifyList)
    fun renameOrder(orderId: String, newName: String) = repo.renameOrder(orderId, newName)
    fun togglePaid(orderId: String) = repo.togglePaid(orderId)
    fun removeOrder(orderId: String) = repo.removeOrder(orderId)
    fun restoreOrder(order: Order) = repo.restoreOrder(order)
    fun removeSuggestion(name: String) = repo.removeSuggestion(name)
    fun updateRestaurantName(name: String) = repo.updateRestaurantName(name)
    fun clearOrders(clearRestaurant: Boolean = false) = repo.clearOrders(clearRestaurant)
    fun restoreBill(snapshot: Pair<List<Order>, String>) = repo.restoreBill(snapshot)

    fun getSavedLogs() = repo.getSavedLogs()
    fun deleteLog(log: String) = repo.deleteLog(log)
    fun isCurrentSessionSaved() = repo.isCurrentSessionSaved()
    fun saveSessionLog() = repo.saveSessionLog()
    fun generateFullReceiptText() = repo.generateFullReceiptText()
    fun generateShareText() = repo.generateShareText()
}
