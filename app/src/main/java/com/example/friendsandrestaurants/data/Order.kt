package com.example.friendsandrestaurants.data

import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

data class FoodItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var price: Double = 0.0,
    var rawPriceExpression: String? = null
)

data class Order(
    val id: String = UUID.randomUUID().toString(),
    var friendName: String,
    var foodItem: String = "",
    var price: Double = 0.0,
    var paid: Double = 0.0,
    var previousPaid: Double = 0.0,
    var isDone: Boolean = false,
    var rawPriceExpression: String? = null,
    var rawPaidExpression: String? = null,
    val items: MutableList<FoodItem> = mutableListOf(),
    /** Set when a guest added this order from the shared web page; only that browser may edit it. */
    var ownerToken: String? = null
) {
    /** Positive = refund owed to this friend, negative = they still owe. Rounded to cents. */
    val cashback: Double
        get() = roundToCents(paid - price)

    val status: Status
        get() = when {
            cashback < 0 -> Status.DUE
            cashback > 0 -> Status.REFUND
            else -> Status.SETTLED
        }

    /** True when nothing has been entered for this friend yet. */
    val isEmpty: Boolean
        get() = isZero(price) && isZero(paid) && items.all { it.name.isBlank() }

    /**
     * `items` is the source of truth. `foodItem`, `price` and `rawPriceExpression` are derived
     * from it (they are kept for the receipt text and for older saved data).
     * Orders saved before multi-item support have no items, so one is created from the old fields.
     */
    fun syncItems() {
        if (items.isEmpty()) {
            items.add(FoodItem(name = foodItem, price = price, rawPriceExpression = rawPriceExpression))
        }
        price = items.sumOf { it.price }
        foodItem = items.map { it.name.trim() }.filter { it.isNotBlank() }.joinToString(", ")
        rawPriceExpression = if (items.size == 1) items[0].rawPriceExpression else null
    }

    /**
     * "Paid" means the friend covered exactly their share. If the bill or the paid amount changes
     * afterwards, the flag no longer holds, so it is cleared and the amount actually paid is kept.
     */
    fun reconcilePaidStatus() {
        if (isDone && !isZero(paid - price)) {
            isDone = false
            previousPaid = paid
        }
    }

    /** Toggles the "paid in full" state, remembering what was paid before so it can be restored. */
    fun togglePaid() {
        if (!isDone) {
            previousPaid = paid
            paid = price
            rawPaidExpression = rawPriceExpression
            isDone = true
        } else {
            paid = previousPaid
            rawPaidExpression = null
            isDone = false
        }
    }

    fun deepCopy(): Order = copy(items = items.map { it.copy() }.toMutableList())

    enum class Status { DUE, REFUND, SETTLED }

    companion object {
        fun roundToCents(value: Double): Double {
            val rounded = (value * 100).roundToLong() / 100.0
            return if (rounded == 0.0) 0.0 else rounded // normalises -0.0
        }

        fun isZero(value: Double): Boolean = abs(value) < 0.005
    }
}
