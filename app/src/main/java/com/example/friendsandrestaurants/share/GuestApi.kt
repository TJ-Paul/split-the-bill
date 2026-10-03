package com.example.friendsandrestaurants.share

import com.example.friendsandrestaurants.BillRepository
import com.example.friendsandrestaurants.BillSummary
import com.example.friendsandrestaurants.ReceiptFormatter
import com.example.friendsandrestaurants.data.Order
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/** Remembers which browsers asked for the bill recently, to show "N people viewing" to the host. */
class ViewerTracker(private val windowMs: Long = 45_000) {
    private val lastSeen = ConcurrentHashMap<String, Long>()

    fun seen(key: String) {
        if (lastSeen.size > 500) lastSeen.clear()
        lastSeen[key] = System.currentTimeMillis()
    }

    fun count(): Int {
        val cutoff = System.currentTimeMillis() - windowMs
        lastSeen.entries.removeIf { it.value < cutoff }
        return lastSeen.size
    }
}

/**
 * The guest web page and its API.
 *
 * - `GET  /`                     the page
 * - `GET  /api/state?token&since` the bill; with `since`, waits until it changes (long polling)
 * - `POST /api/orders`            add yourself `{token, name, items:[{name, price}]}`
 * - `PUT  /api/orders/{id}`       change your own order (same body)
 * - `DELETE /api/orders/{id}?token=` take your own order off the bill
 *
 * A guest owns the orders created with their browser's random token and can change only those.
 * The host's app can change everything. All bill reads and writes run on the main thread.
 */
class GuestApi(
    private val repo: BillRepository,
    private val viewers: ViewerTracker,
    private val loadPage: () -> ByteArray
) : (HttpRequest) -> HttpResponse {

    private val page: ByteArray by lazy(loadPage)

    override fun invoke(request: HttpRequest): HttpResponse {
        if (!isAllowedHost(request.headers["host"])) {
            return HttpResponse.text(421, "Open this page with the address shown on the host's phone.")
        }
        return try {
            route(request)
        } catch (e: GuestError) {
            error(e.status, e.message.orEmpty(), e.field)
        } catch (e: TimeoutException) {
            error(503, "The host's phone is busy. Please try again.")
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        val path = request.path.trimEnd('/').ifEmpty { "/" }
        val method = request.method
        return when {
            path == "/" || path == "/index.html" ->
                if (method == "GET") page() else methodNotAllowed()
            path == "/api/state" ->
                if (method == "GET") state(request) else methodNotAllowed()
            path == "/api/orders" ->
                if (method == "POST") create(request) else methodNotAllowed()
            path.startsWith("/api/orders/") -> {
                val id = path.removePrefix("/api/orders/")
                if (id.isEmpty() || '/' in id) return notFound()
                when (method) {
                    "PUT" -> update(request, id)
                    "DELETE" -> remove(request, id)
                    else -> methodNotAllowed()
                }
            }
            else -> notFound()
        }
    }

    // ------------------------------------------------------------------ routes

    private fun page() = HttpResponse(
        200, "text/html; charset=utf-8", page,
        mapOf(
            "Content-Security-Policy" to "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "img-src data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            "Referrer-Policy" to "no-referrer"
        )
    )

    private fun state(request: HttpRequest): HttpResponse {
        val token = request.query["token"]?.takeIf { GuestInput.isValidToken(it) }
        viewers.seen(token ?: "ip:${request.remoteAddress}")
        val since = request.query["since"]?.toLongOrNull()
        if (since != null) {
            val revision = repo.awaitChange(since, LONG_POLL_MS)
            if (revision == since) return HttpResponse.json(200, JSONObject().put("rev", revision).put("unchanged", true).toString())
        }
        val json = MainThread.call { snapshot(token) }
        return HttpResponse.json(200, json.toString())
    }

    private fun create(request: HttpRequest): HttpResponse {
        val body = GuestInput.parseBody(request.body)
        val token = GuestInput.requireToken(body.optString("token"))
        val name = GuestInput.parseName(body.opt("name"))
        val items = GuestInput.parseItems(body.opt("items"))
        if (items.isEmpty()) throw GuestError(400, "Add at least one thing you ordered.", "items")

        return MainThread.call {
            val orders = repo.currentOrders
            val sameName = orders.firstOrNull { it.friendName.equals(name, ignoreCase = true) }
            val id = when {
                // Their own order already has this name, e.g. a retried request: just update it.
                sameName != null && sameName.ownerToken == token -> {
                    applyEdit(sameName, name, items)
                    sameName.id
                }
                // The host added this name without any order yet: the guest fills it in.
                sameName != null && sameName.ownerToken == null && sameName.isEmpty -> {
                    applyEdit(sameName, name, items, claimFor = token)
                    sameName.id
                }
                sameName != null -> throw nameTaken(name)
                else -> {
                    if (orders.count { it.ownerToken == token } >= MAX_ORDERS_PER_GUEST) {
                        throw GuestError(429, "You've added $MAX_ORDERS_PER_GUEST people from this phone already. Ask the host to add more.")
                    }
                    if (orders.size >= MAX_ORDERS_TOTAL) throw GuestError(429, "The bill is full. Ask the host to add you.")
                    val order = Order(friendName = name, ownerToken = token, items = GuestInput.toFoodItems(items))
                    order.syncItems()
                    if (!repo.addFriendOrder(order)) throw GuestError(400, "Please enter your name.", "name")
                    order.id
                }
            }
            ok(id, token)
        }
    }

    private fun update(request: HttpRequest, id: String): HttpResponse {
        val body = GuestInput.parseBody(request.body)
        val token = GuestInput.requireToken(body.optString("token"))
        val name = GuestInput.parseName(body.opt("name"))
        val items = GuestInput.parseItems(body.opt("items"))

        return MainThread.call {
            val existing = repo.currentOrders.firstOrNull { it.id == id }
                ?: throw GuestError(404, "The host took this order off the bill. Save again to add it back.", "gone")
            if (existing.ownerToken != token) throw GuestError(403, "You can only change your own order.")
            if (repo.currentOrders.any { it.id != id && it.friendName.equals(name, ignoreCase = true) }) throw nameTaken(name)
            applyEdit(existing, name, items)
            ok(id, token)
        }
    }

    private fun remove(request: HttpRequest, id: String): HttpResponse {
        val token = GuestInput.requireToken(request.query["token"])
        return MainThread.call {
            val existing = repo.currentOrders.firstOrNull { it.id == id }
            if (existing != null) {
                if (existing.ownerToken != token) throw GuestError(403, "You can only remove your own order.")
                repo.removeOrder(id)
            }
            ok(id, token)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Main thread only. */
    private fun applyEdit(existing: Order, name: String, items: List<GuestInput.ItemInput>, claimFor: String? = null) {
        val copy = existing.deepCopy()
        copy.friendName = name
        if (claimFor != null) copy.ownerToken = claimFor
        val newItems = GuestInput.toFoodItems(items, existing.items)
        copy.items.clear()
        copy.items.addAll(newItems)
        copy.syncItems()
        // A changed bill un-marks "paid in full", just like editing in the app.
        copy.reconcilePaidStatus()
        repo.updateOrder(copy)
    }

    private fun nameTaken(name: String) = GuestError(
        409, "There's already a “$name” on the bill. Add your surname or an initial so the host can tell you apart.", "name"
    )

    /** Main thread only. */
    private fun ok(id: String, token: String): HttpResponse =
        HttpResponse.json(200, JSONObject().put("ok", true).put("id", id).put("state", snapshot(token)).toString())

    /** Main thread only. */
    private fun snapshot(token: String?): JSONObject {
        val orders = ReceiptFormatter.sortForReceipt(repo.currentOrders)
        val summary = BillSummary.of(orders)
        val list = JSONArray()
        orders.forEach { list.put(orderJson(it, token)) }
        return JSONObject()
            .put("rev", repo.revision)
            .put("restaurant", repo.restaurantName.trim())
            .put("orders", list)
            .put(
                "summary", JSONObject()
                    .put("people", summary.friendCount)
                    .put("total", summary.totalBill)
                    .put("paid", summary.totalPaid)
                    .put("due", summary.totalDue)
                    .put("refund", summary.totalRefund)
            )
    }

    private fun orderJson(order: Order, token: String?): JSONObject {
        val items = JSONArray()
        order.items
            .filter { it.name.isNotBlank() || !Order.isZero(it.price) }
            .forEach { item ->
                items.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("price", Order.roundToCents(item.price))
                        .put("expr", item.rawPriceExpression)
                )
            }
        return JSONObject()
            .put("id", order.id)
            .put("name", order.friendName)
            .put("items", items)
            .put("total", Order.roundToCents(order.price))
            .put("paid", Order.roundToCents(order.paid))
            .put("balance", order.cashback)
            .put("isPaid", order.isDone)
            .put("mine", token != null && order.ownerToken == token)
            .put("guest", order.ownerToken != null)
    }

    private fun error(status: Int, message: String, field: String? = null): HttpResponse {
        val json = JSONObject().put("ok", false).put("error", message)
        if (field != null) json.put("field", field)
        return HttpResponse.json(status, json.toString())
    }

    private fun notFound() = error(404, "Not found")
    private fun methodNotAllowed() = error(405, "Not allowed")

    companion object {
        const val LONG_POLL_MS = 20_000L
        const val MAX_ORDERS_PER_GUEST = 8
        const val MAX_ORDERS_TOTAL = 150

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /**
         * Only answer requests addressed to an IP (as in the QR code) or localhost. This stops other
         * websites open in a guest's browser from reaching the bill through a look-alike domain name.
         */
        fun isAllowedHost(host: String?): Boolean {
            if (host.isNullOrBlank()) return true
            val value = host.trim().lowercase()
            if (value.startsWith("[")) return value.contains("]") // IPv6 literal
            val name = value.substringBeforeLast(':')
            return name == "localhost" || IPV4.matches(name)
        }
    }
}
