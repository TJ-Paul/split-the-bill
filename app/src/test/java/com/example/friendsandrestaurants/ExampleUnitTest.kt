package com.example.friendsandrestaurants

import com.example.friendsandrestaurants.data.FoodItem
import com.example.friendsandrestaurants.data.Order
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun priceCalculator_simpleAddition() {
        val result = PriceCalculator.evaluate("234+345+86")
        assertTrue(result.isSuccess)
        assertEquals(665.0, result.getOrThrow(), 0.001)
        assertEquals("665", PriceCalculator.formatResult(result.getOrThrow()))
    }

    @Test
    fun priceCalculator_operationsWithSpacesAndDecimals() {
        val result = PriceCalculator.evaluate("100.5 + 200.25 - 50.75")
        assertTrue(result.isSuccess)
        assertEquals(250.0, result.getOrThrow(), 0.001)
    }

    @Test
    fun priceCalculator_multiplicationAndDivisionPrecedence() {
        val result = PriceCalculator.evaluate("10 + 20 * 3 - 5 / 2")
        assertTrue(result.isSuccess)
        assertEquals(67.5, result.getOrThrow(), 0.001)
        assertEquals("67.5", PriceCalculator.formatResult(result.getOrThrow()))
    }

    @Test
    fun priceCalculator_invalidCharacters() {
        assertTrue(PriceCalculator.hasInvalidChars("234+345a"))
        val result = PriceCalculator.evaluate("234+345a")
        assertTrue(result.isFailure)
    }

    @Test
    fun priceCalculator_incompleteOrInvalidSyntax() {
        assertTrue(PriceCalculator.evaluate("234+").isFailure)
        assertTrue(PriceCalculator.evaluate("234++5").isFailure)
        assertTrue(PriceCalculator.evaluate("10 / 0").isFailure)
        assertTrue(PriceCalculator.evaluate("2.3.4").isFailure)
    }

    @Test
    fun priceCalculator_emptyAndSingleNumber() {
        assertEquals(0.0, PriceCalculator.evaluate("").getOrThrow(), 0.001)
        assertEquals(500.0, PriceCalculator.evaluate("500").getOrThrow(), 0.001)
        assertFalse(PriceCalculator.hasOperators("500"))
        assertTrue(PriceCalculator.hasOperators("234+345"))
    }

    @Test
    fun nameFormatter_commaFullstopSpaceSeparation() {
        assertEquals("Arib", NameFormatter.formatName("arib"))
        assertEquals("Arib, Nanbun", NameFormatter.formatName("arib,nanbun"))
        assertEquals("Arib, Nanbun", NameFormatter.formatName("arib, nanbun"))
        assertEquals("Arib. Nanbun", NameFormatter.formatName("arib.nanbun"))
        assertEquals("Arib. Nanbun", NameFormatter.formatName("arib. nanbun"))
        assertEquals("Arib Nanbun", NameFormatter.formatName("arib nanbun"))
        assertEquals("Arib, Nanbun. Charlie David", NameFormatter.formatName("arib, nanbun. charlie david"))
        assertEquals("Arib, Bob", NameFormatter.formatName("aRIB , bOB"))
    }

    @Test
    fun multipleFoodItems_syncAndSum() {
        val order = Order(
            friendName = "Arib",
            foodItem = "Nan",
            price = 30.0
        )
        order.syncItems()
        assertEquals(1, order.items.size)
        assertEquals("Nan", order.items[0].name)
        assertEquals(30.0, order.items[0].price, 0.001)

        order.items.add(FoodItem(name = "Chicken", price = 150.0))
        order.syncItems()
        assertEquals(2, order.items.size)
        assertEquals(180.0, order.price, 0.001)
        assertEquals("Nan, Chicken", order.foodItem)
    }

    // ---------------------------------------------------------------- regression tests

    @Test
    fun removingItem_keepsRemainingItemIntact() {
        // Previously, going from 2 items to 1 overwrote the survivor with the joined name and old total.
        val order = Order(friendName = "Arib")
        order.items.add(FoodItem(name = "nan", price = 30.0))
        order.items.add(FoodItem(name = "chicken", price = 150.0, rawPriceExpression = "100+50"))
        order.syncItems()

        order.items.removeAt(0)
        order.syncItems()

        assertEquals(1, order.items.size)
        assertEquals("chicken", order.items[0].name)
        assertEquals(150.0, order.items[0].price, 0.001)
        assertEquals("chicken", order.foodItem)
        assertEquals(150.0, order.price, 0.001)
        assertEquals("100+50", order.rawPriceExpression)
    }

    @Test
    fun cashback_isRoundedAndKeepsHalfAmounts() {
        val noise = Order(friendName = "A", paid = 0.1 + 0.2).apply { items.add(FoodItem(price = 0.3)); syncItems() }
        assertEquals(0.0, noise.cashback, 0.0)
        assertEquals(Order.Status.SETTLED, noise.status)

        // A half-taka due used to be truncated to 0 and shown as "Settled".
        val half = Order(friendName = "B", paid = 99.5).apply { items.add(FoodItem(price = 100.0)); syncItems() }
        assertEquals(-0.5, half.cashback, 0.0)
        assertEquals(Order.Status.DUE, half.status)
    }

    @Test
    fun togglePaid_restoresPreviousAmount() {
        val order = Order(friendName = "A", paid = 40.0).apply { items.add(FoodItem(price = 100.0)); syncItems() }
        order.togglePaid()
        assertTrue(order.isDone)
        assertEquals(100.0, order.paid, 0.0)
        order.togglePaid()
        assertFalse(order.isDone)
        assertEquals(40.0, order.paid, 0.0)
    }

    @Test
    fun reconcilePaid_clearsFlagWhenBillChanges() {
        val order = Order(friendName = "A").apply { items.add(FoodItem(price = 100.0)); syncItems() }
        order.togglePaid()
        order.items.add(FoodItem(name = "drink", price = 30.0))
        order.syncItems()
        order.reconcilePaidStatus()
        assertFalse(order.isDone)
        assertEquals(100.0, order.paid, 0.0)
        assertEquals(-30.0, order.cashback, 0.0)
    }

    @Test
    fun deepCopy_isIndependentAndEqual() {
        val order = Order(friendName = "A").apply { items.add(FoodItem(name = "x", price = 1.0)); syncItems() }
        val copy = order.deepCopy()
        assertEquals(order, copy)
        copy.items[0].price = 2.0
        assertEquals(1.0, order.items[0].price, 0.0)
        assertNotEquals(order, copy)
    }

    @Test
    fun formatMoney_groupsThousandsAndTrimsDecimals() {
        assertEquals("1,211,788", PriceCalculator.formatMoney(1211788.0))
        assertEquals("333.33", PriceCalculator.formatMoney(1000.0 / 3))
        assertEquals("0.5", PriceCalculator.formatMoney(0.5))
        assertEquals("0", PriceCalculator.formatMoney(-0.0))
        assertEquals("", PriceCalculator.fieldText(0.0))
        assertEquals("250", PriceCalculator.fieldText(250.0))
    }

    @Test
    fun resolveRawExpression_keepsExpressionWhenFieldShowsItsResult() {
        assertEquals("100+50", PriceCalculator.resolveRawExpression("100+50", 150.0, null))
        // After formatting, the field shows "150"; the original expression must survive.
        assertEquals("100+50", PriceCalculator.resolveRawExpression("150", 150.0, "100+50"))
        // A different plain number replaces it.
        assertNull(PriceCalculator.resolveRawExpression("200", 200.0, "100+50"))
    }

    @Test
    fun dedupeNames_skipsExistingAndRepeats() {
        val (toAdd, skipped) = OrderViewModel.dedupeNames(
            listOf("arib", "  ", "Bob", "bob", "carl", "ARIB"),
            existing = listOf("Carl")
        )
        assertEquals(listOf("Arib", "Bob"), toAdd)
        assertEquals(3, skipped)
    }

    @Test
    fun suggestionName_stripsSharedSuffix() {
        assertEquals("pizza", OrderViewModel.suggestionName("pizza (shared ÷3)"))
        assertEquals("pizza", OrderViewModel.suggestionName("Pizza"))
    }

    @Test
    fun billSummary_separatesDueAndRefund() {
        val a = Order(friendName = "A", paid = 300.0).apply { items.add(FoodItem(price = 100.0)); syncItems() }
        val b = Order(friendName = "B", paid = 0.0).apply { items.add(FoodItem(price = 200.0)); syncItems() }
        val s = BillSummary.of(listOf(a, b))
        assertEquals(300.0, s.totalBill, 0.0)
        assertEquals(300.0, s.totalPaid, 0.0)
        assertEquals(200.0, s.totalDue, 0.0)
        assertEquals(200.0, s.totalRefund, 0.0)
        assertEquals(0.0, s.net, 0.0)
        assertFalse(s.isAllSettled)
    }

    @Test
    fun receiptTable_showsDecimalsAndTotals() {
        val a = Order(friendName = "Arib", paid = 99.5).apply { items.add(FoodItem(name = "naan", price = 100.0)); syncItems() }
        val table = ReceiptFormatter.buildTable(listOf(a))
        assertTrue(table.contains("DUE: 0.5 tk"))
        assertTrue(table.contains("TOTAL BILL:"))
        assertTrue(table.contains("OVERALL DUE:    0.5 tk"))

        val entry = LogEntry.parse("[2026-10-03 20:15:00] Nando's\n$table")
        assertEquals("Nando's", entry.restaurant)
        assertEquals("2026-10-03 20:15:00", entry.timestamp)
        assertEquals("100", entry.total)
    }

    @Test
    fun shareText_listsEveryoneAndTotals() {
        val a = Order(friendName = "Arib", paid = 160.0).apply {
            items.add(FoodItem(name = "biryani", price = 250.0)); items.add(FoodItem(name = "naan", price = 30.0)); syncItems()
        }
        val b = Order(friendName = "Bob", paid = 220.0).apply { items.add(FoodItem(name = "pizza", price = 200.0)); syncItems() }
        val text = ReceiptFormatter.buildShareText("Nando's", listOf(a, b))
        assertTrue(text.startsWith("🧾 Nando's"))
        assertTrue(text.contains("• Arib — 280 tk (biryani, naan)"))
        assertTrue(text.contains("DUE 120 tk"))
        assertTrue(text.contains("REFUND 20 tk"))
        assertTrue(text.contains("Total bill: 480 tk"))
        assertTrue(text.contains("Overall due: 100 tk"))
    }

    @Test
    fun initials_useFirstTwoWords() {
        assertEquals("AH", OrderAdapter.initials("Arib Hasan"))
        assertEquals("AN", OrderAdapter.initials("Arib, Nanbun"))
        assertEquals("B", OrderAdapter.initials("Bob"))
        assertEquals("?", OrderAdapter.initials(""))
    }
}
