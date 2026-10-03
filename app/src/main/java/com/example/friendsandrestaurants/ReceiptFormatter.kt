package com.example.friendsandrestaurants

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.example.friendsandrestaurants.PriceCalculator.formatMoney
import com.example.friendsandrestaurants.data.Order
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Totals for a bill. Due/refund are summed per friend, so one person's refund never hides another's debt. */
data class BillSummary(
    val friendCount: Int,
    val itemCount: Int,
    val totalBill: Double,
    val totalPaid: Double,
    val totalDue: Double,
    val totalRefund: Double
) {
    val net: Double get() = Order.roundToCents(totalPaid - totalBill)
    val isAllSettled: Boolean get() = Order.isZero(totalDue) && Order.isZero(totalRefund)

    companion object {
        val EMPTY = BillSummary(0, 0, 0.0, 0.0, 0.0, 0.0)

        fun of(orders: List<Order>): BillSummary {
            var due = 0.0
            var refund = 0.0
            orders.forEach {
                val cb = it.cashback
                if (cb < 0) due += -cb else if (cb > 0) refund += cb
            }
            return BillSummary(
                friendCount = orders.size,
                itemCount = orders.sumOf { o -> o.items.count { it.name.isNotBlank() || !Order.isZero(it.price) } },
                totalBill = Order.roundToCents(orders.sumOf { it.price }),
                totalPaid = Order.roundToCents(orders.sumOf { it.paid }),
                totalDue = Order.roundToCents(due),
                totalRefund = Order.roundToCents(refund)
            )
        }
    }
}

/** A saved history entry. Stored format: "[yyyy-MM-dd HH:mm:ss] Restaurant\n<receipt table>". */
data class LogEntry(
    val raw: String,
    val timestamp: String,
    val restaurant: String,
    val body: String,
    val total: String?
) {
    val displayDate: String get() = ReceiptFormatter.prettyTimestamp(timestamp)

    companion object {
        private val TOTAL_REGEX = Regex("""TOTAL BILL:\s*([0-9.,]+)\s*tk""")

        fun parse(log: String): LogEntry {
            val title = log.substringBefore("\n")
            val body = if (log.contains("\n")) log.substringAfter("\n") else ""
            val hasTimestamp = title.startsWith("[") && title.contains("]")
            val timestamp = if (hasTimestamp) title.substringAfter("[").substringBefore("]") else ""
            val restaurant = (if (hasTimestamp) title.substringAfter("]") else title).trim()
            val total = TOTAL_REGEX.find(body)?.groupValues?.get(1)
            return LogEntry(log, timestamp, restaurant, body, total)
        }
    }
}

object ReceiptFormatter {

    const val STORAGE_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"

    fun storageTimestamp(date: Date = Date()): String =
        SimpleDateFormat(STORAGE_TIMESTAMP_PATTERN, Locale.US).format(date)

    /** "2026-10-03 20:15:00" -> "Sat, 3 Oct 2026 · 8:15 PM". Falls back to the input if unparseable. */
    fun prettyTimestamp(timestamp: String): String {
        if (timestamp.isBlank()) return ""
        val parsed = runCatching {
            SimpleDateFormat(STORAGE_TIMESTAMP_PATTERN, Locale.US).apply { isLenient = false }.parse(timestamp)
        }.getOrNull() ?: return timestamp
        return prettyDate(parsed)
    }

    fun prettyDate(date: Date): String =
        SimpleDateFormat("EEE, d MMM yyyy · h:mm a", Locale.getDefault()).format(date)

    fun sortForReceipt(orders: List<Order>): List<Order> =
        orders.sortedWith(
            compareBy<Order> { it.status.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.friendName }
        )

    /** Fixed-width table used for the "Table" view and saved history logs. */
    fun buildTable(orders: List<Order>): String {
        if (orders.isEmpty()) return "No friends or orders added yet."
        val ordersList = sortForReceipt(orders)

        fun paidLine(o: Order) = "${formatMoney(o.paid)} / ${formatMoney(o.price)} tk"
        fun statusLine(o: Order): String? {
            val cb = o.cashback
            if (cb == 0.0) return null
            val label = if (cb > 0) "REFUND" else "DUE"
            return "$label: ${formatMoney(abs(cb))} tk"
        }

        var maxNameLen = "FRIEND".length
        var maxItemLen = "ITEM & INFO".length
        ordersList.forEach {
            maxNameLen = maxOf(maxNameLen, it.friendName.length)
            maxItemLen = maxOf(maxItemLen, it.foodItem.length, paidLine(it).length, statusLine(it)?.length ?: 0)
        }

        val c1W = (maxNameLen + 6).coerceIn(12, 20)
        val c2W = (maxItemLen + 6).coerceIn(16, 28)
        val sb = StringBuilder()

        fun line(c: String = "-") = "+${c.repeat(c1W)}+${c.repeat(c2W)}+\n"

        fun wrap(text: String, width: Int): List<String> {
            if (text.isEmpty()) return listOf("")
            val contentWidth = (width - 2).coerceAtLeast(1)
            val words = text.split(" ")
            val lines = mutableListOf<String>()
            var current = ""
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (candidate.length <= contentWidth) {
                    current = candidate
                } else {
                    if (current.isNotEmpty()) lines.add(current)
                    // Very long single words still get hard-wrapped.
                    val chunks = word.chunked(contentWidth)
                    lines.addAll(chunks.dropLast(1))
                    current = chunks.last()
                }
            }
            if (current.isNotEmpty() || lines.isEmpty()) lines.add(current)
            return lines
        }

        fun row(s1: String, s2: String): String {
            val lines1 = wrap(s1, c1W)
            val lines2 = wrap(s2, c2W)
            val res = StringBuilder()
            for (i in 0 until maxOf(lines1.size, lines2.size)) {
                res.append("|${center(lines1.getOrElse(i) { "" }, c1W)}|${center(lines2.getOrElse(i) { "" }, c2W)}|\n")
            }
            return res.toString()
        }

        sb.append(line())
        sb.append(row("FRIEND", "ITEM & INFO"))
        sb.append(line("="))

        for (order in ordersList) {
            sb.append(row(order.friendName, order.foodItem))
            sb.append(row("", paidLine(order)))
            statusLine(order)?.let { sb.append(row("", it)) }
            sb.append(line())
        }

        val summary = BillSummary.of(ordersList)
        sb.append("\n")
        val totalWidth = c1W + c2W + 3

        fun footerRow(label: String, value: String): String {
            val space = totalWidth - label.length - value.length
            return label + " ".repeat(maxOf(1, space)) + value + "\n"
        }

        sb.append(footerRow("TOTAL BILL:", "${formatMoney(summary.totalBill)} tk"))
        sb.append(footerRow("TOTAL PAID:", "${formatMoney(summary.totalPaid)} tk"))
        sb.append("-".repeat(totalWidth)).append("\n")

        val net = summary.net
        when {
            net > 0 -> sb.append("OVERALL REFUND: ${formatMoney(net)} tk 💰")
            net < 0 -> sb.append("OVERALL DUE:    ${formatMoney(abs(net))} tk ⚠️")
            else -> sb.append("STATUS:         ALL SETTLED ✅")
        }
        return sb.toString()
    }

    /** Plain text that reads well in chat apps (no fixed-width table). */
    fun buildShareText(restaurant: String, orders: List<Order>, date: Date = Date()): String {
        val sb = StringBuilder()
        sb.append("🧾 ").append(restaurant.ifBlank { "Bill" }).append("\n")
        sb.append(prettyDate(date)).append("\n\n")
        if (orders.isEmpty()) {
            sb.append("No friends or orders added yet.")
            return sb.toString()
        }
        for (order in sortForReceipt(orders)) {
            sb.append("• ").append(order.friendName).append(" — ").append(formatMoney(order.price)).append(" tk")
            if (order.foodItem.isNotBlank()) sb.append(" (").append(order.foodItem).append(")")
            sb.append("\n  Paid ").append(formatMoney(order.paid)).append(" tk")
            val cb = order.cashback
            when {
                cb < 0 -> sb.append(" → DUE ").append(formatMoney(-cb)).append(" tk")
                cb > 0 -> sb.append(" → REFUND ").append(formatMoney(cb)).append(" tk")
                else -> sb.append(" ✓ settled")
            }
            sb.append("\n")
        }
        val summary = BillSummary.of(orders)
        sb.append("\nTotal bill: ").append(formatMoney(summary.totalBill)).append(" tk")
        sb.append("\nTotal paid: ").append(formatMoney(summary.totalPaid)).append(" tk")
        val net = summary.net
        when {
            net > 0 -> sb.append("\nOverall refund: ").append(formatMoney(net)).append(" tk 💰")
            net < 0 -> sb.append("\nOverall due: ").append(formatMoney(-net)).append(" tk ⚠️")
            else -> sb.append("\nAll settled ✅")
        }
        return sb.toString()
    }

    /** Colours DUE lines red and REFUND/SETTLED lines green in a receipt table. */
    fun applyColors(text: String, dueColor: Int, refundColor: Int): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(text)
        var currentPos = 0
        for (line in text.split("\n")) {
            if (currentPos >= ssb.length) break
            val lineEnd = (currentPos + line.length).coerceAtMost(ssb.length)
            val color = when {
                line.contains("DUE") -> dueColor
                line.contains("REFUND") || line.contains("ALL SETTLED") -> refundColor
                else -> null
            }
            if (color != null) {
                ssb.setSpan(ForegroundColorSpan(color), currentPos, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            currentPos += line.length + 1
        }
        return ssb
    }

    private fun center(text: String, width: Int): String {
        val padding = width - text.length
        val left = padding / 2
        val right = padding - left
        return " ".repeat(maxOf(0, left)) + text + " ".repeat(maxOf(0, right))
    }
}
