package com.example.friendsandrestaurants.share

import com.example.friendsandrestaurants.NameFormatter
import com.example.friendsandrestaurants.PriceCalculator
import com.example.friendsandrestaurants.data.FoodItem
import com.example.friendsandrestaurants.data.Order
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A request from the web page that can't be applied. [message] is shown to the guest as-is. */
class GuestError(val status: Int, message: String, val field: String? = null) : Exception(message)

/** Validates what guests send from the web page. Prices are re-evaluated here; the page is never trusted. */
object GuestInput {
    const val MAX_NAME_LENGTH = 40
    const val MAX_ITEM_NAME_LENGTH = 60
    const val MAX_PRICE_LENGTH = 60
    const val MAX_ITEMS = 30
    const val MAX_PRICE = 10_000_000.0

    private val TOKEN = Regex("^[a-f0-9]{32}$")

    data class ItemInput(val id: String?, val name: String, val price: Double, val rawExpression: String?)

    fun isValidToken(token: String?): Boolean = token != null && TOKEN.matches(token)

    fun requireToken(token: String?): String {
        if (!isValidToken(token)) throw GuestError(400, "Please reload the page and try again.")
        return token!!
    }

    fun parseName(value: Any?): String {
        val raw = (value as? String).orEmpty()
        val name = NameFormatter.formatName(raw)
        if (name.isBlank()) throw GuestError(400, "Please enter your name.", "name")
        if (name.length > MAX_NAME_LENGTH) throw GuestError(400, "That name is too long (max $MAX_NAME_LENGTH letters).", "name")
        return name
    }

    /** Parses `[{id?, name, price}]`, where price is the text typed (e.g. "120+80"). Empty rows are dropped. */
    fun parseItems(value: Any?): List<ItemInput> {
        val array = value as? JSONArray ?: return emptyList()
        if (array.length() > MAX_ITEMS) throw GuestError(400, "That's a lot of items! Please keep it to $MAX_ITEMS or fewer.", "items")
        val result = mutableListOf<ItemInput>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val field = "items.$i"
            val label = "Item ${i + 1}"
            val name = obj.optString("name", "").trim().replace(Regex("\\s+"), " ")
            if (name.length > MAX_ITEM_NAME_LENGTH) {
                throw GuestError(400, "$label: the name is too long (max $MAX_ITEM_NAME_LENGTH letters).", "$field.name")
            }
            val priceText = normalizeExpression(obj.optString("price", ""))
            if (priceText.length > MAX_PRICE_LENGTH) throw GuestError(400, "$label: the price is too long.", "$field.price")
            val price = PriceCalculator.evaluate(priceText).getOrElse {
                throw GuestError(400, "$label: “$priceText” isn't a price we can work out. Use numbers with + − × ÷.", "$field.price")
            }
            if (price < 0) throw GuestError(400, "$label: the price can't be below zero.", "$field.price")
            if (price > MAX_PRICE) throw GuestError(400, "$label: that price looks too big.", "$field.price")
            if (name.isEmpty() && Order.isZero(price)) continue
            val raw = PriceCalculator.resolveRawExpression(priceText, price, null)
            val id = obj.optString("id", "").takeIf { it.isNotBlank() && it.length <= 64 }
            result.add(ItemInput(id, name, price, raw))
        }
        return result
    }

    /**
     * Converts what phone keyboards may type into what the calculator understands:
     * × ÷ − and x for operators, and Bengali/Hindi/Arabic digits.
     */
    fun normalizeExpression(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.trim()) {
            sb.append(
                when (ch) {
                    '×', 'x', 'X', '✕' -> '*'
                    '÷' -> '/'
                    '−', '–' -> '-'
                    in '০'..'৯' -> '0' + (ch - '০') // Bengali
                    in '०'..'९' -> '0' + (ch - '०') // Devanagari
                    in '٠'..'٩' -> '0' + (ch - '٠') // Arabic-Indic
                    in '۰'..'۹' -> '0' + (ch - '۰') // Eastern Arabic-Indic
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** Builds food items, keeping the ids of items the order already had so edits stay stable. */
    fun toFoodItems(items: List<ItemInput>, existing: List<FoodItem> = emptyList()): MutableList<FoodItem> {
        val unusedIds = existing.map { it.id }.toMutableSet()
        val result = items.map {
            FoodItem(
                id = it.id?.takeIf { id -> unusedIds.remove(id) } ?: UUID.randomUUID().toString(),
                name = it.name,
                price = it.price,
                rawPriceExpression = it.rawExpression
            )
        }.toMutableList()
        // An order always has at least one (possibly blank) item row.
        if (result.isEmpty()) result.add(FoodItem())
        return result
    }

    fun parseBody(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: Exception) {
        throw GuestError(400, "Please reload the page and try again.")
    }
}
