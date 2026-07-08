package com.example.friendsandrestaurants.data

import java.util.UUID

data class Order(
    val id: String = UUID.randomUUID().toString(),
    val friendName: String,
    var foodItem: String = "",
    var price: Double = 0.0,
    var paid: Double = 0.0,
    var previousPaid: Double = 0.0,
    var isDone: Boolean = false
) {
    val cashback: Double
        get() = paid - price
}