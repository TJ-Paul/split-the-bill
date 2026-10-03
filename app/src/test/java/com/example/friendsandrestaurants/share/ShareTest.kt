package com.example.friendsandrestaurants.share

import com.example.friendsandrestaurants.PriceCalculator
import com.example.friendsandrestaurants.data.FoodItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShareTest {

    // ------------------------------------------------------------------ calculator parity

    /**
     * The guest page has its own copy of the calculator. Both are checked against the same cases
     * (calc_cases.tsv); the page's copy is checked with the same file outside the Android build.
     */
    @Test
    fun calculator_matchesSharedCases() {
        val text = javaClass.classLoader!!.getResource("calc_cases.tsv")!!.readText()
        val cases = text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(cases.size > 30)
        for (line in cases) {
            val (expr, expected) = line.split('\t')
            val result = PriceCalculator.evaluate(GuestInput.normalizeExpression(expr))
            if (expected == "ERR") {
                assertTrue("\"$expr\" should be invalid but gave ${result.getOrNull()}", result.isFailure)
            } else {
                assertTrue("\"$expr\" should be valid", result.isSuccess)
                assertEquals("\"$expr\"", expected.toDouble(), result.getOrThrow(), 1e-9)
            }
        }
    }

    // ------------------------------------------------------------------ guest input

    private fun items(vararg pairs: Pair<String, String>) =
        JSONArray().apply { pairs.forEach { (n, p) -> put(JSONObject().put("name", n).put("price", p)) } }

    @Test
    fun parseItems_evaluatesSumsAndDropsEmptyRows() {
        val parsed = GuestInput.parseItems(items("Biryani" to "250×2", "" to "", "naan" to "40", "tea" to ""))
        assertEquals(3, parsed.size)
        assertEquals(500.0, parsed[0].price, 0.0)
        assertEquals("250*2", parsed[0].rawExpression)
        assertEquals(40.0, parsed[1].price, 0.0)
        assertNull(parsed[1].rawExpression)
        assertEquals("tea", parsed[2].name)
        assertEquals(0.0, parsed[2].price, 0.0)
    }

    @Test
    fun parseItems_rejectsBadPricesWithTheRightField() {
        fun errorFor(vararg pairs: Pair<String, String>): GuestError =
            assertThrows(GuestError::class.java) { GuestInput.parseItems(items(*pairs)) }

        assertEquals("items.1.price", errorFor("a" to "10", "b" to "12+").field)
        assertEquals("items.0.price", errorFor("a" to "-5").field)
        assertEquals("items.0.price", errorFor("a" to "100000000").field)
        assertEquals("items.0.price", errorFor("a" to "5/0").field)
        assertEquals("items.0.name", errorFor("x".repeat(61) to "1").field)
        assertEquals("items", errorFor(*Array(31) { "i$it" to "1" }).field)
    }

    @Test
    fun parseName_formatsAndValidates() {
        assertEquals("Rahim Khan", GuestInput.parseName("  rahim   khan "))
        assertEquals("name", assertThrows(GuestError::class.java) { GuestInput.parseName("   ") }.field)
        assertEquals("name", assertThrows(GuestError::class.java) { GuestInput.parseName(null) }.field)
        assertEquals("name", assertThrows(GuestError::class.java) { GuestInput.parseName("a".repeat(41)) }.field)
    }

    @Test
    fun tokens_mustBe32Hex() {
        assertTrue(GuestInput.isValidToken("0123456789abcdef0123456789abcdef"))
        assertFalse(GuestInput.isValidToken("0123456789ABCDEF0123456789abcdef"))
        assertFalse(GuestInput.isValidToken("abc"))
        assertFalse(GuestInput.isValidToken(null))
    }

    @Test
    fun toFoodItems_keepsOnlyKnownIdsOnce() {
        val existing = listOf(FoodItem(id = "a"), FoodItem(id = "b"))
        val result = GuestInput.toFoodItems(
            listOf(
                GuestInput.ItemInput("a", "one", 1.0, null),
                GuestInput.ItemInput("a", "dup", 2.0, null),
                GuestInput.ItemInput("someone-elses", "three", 3.0, null)
            ),
            existing
        )
        assertEquals("a", result[0].id)
        assertTrue(result[1].id != "a")
        assertTrue(result[2].id != "someone-elses")
        assertEquals(1, GuestInput.toFoodItems(emptyList()).size) // always one row
    }

    @Test
    fun allowedHosts_areIpLiteralsOrLocalhost() {
        assertTrue(GuestApi.isAllowedHost("192.168.43.1:8080"))
        assertTrue(GuestApi.isAllowedHost("10.0.0.5"))
        assertTrue(GuestApi.isAllowedHost("localhost:8080"))
        assertTrue(GuestApi.isAllowedHost(null))
        assertFalse(GuestApi.isAllowedHost("evil.example.com"))
        assertFalse(GuestApi.isAllowedHost("evil.example.com:8080"))
        assertFalse(GuestApi.isAllowedHost("192.168.1.1.evil.com"))
    }

    @Test
    fun viewerTracker_countsRecentOnly() {
        val tracker = ViewerTracker(windowMs = 50)
        tracker.seen("a")
        tracker.seen("b")
        tracker.seen("a")
        assertEquals(2, tracker.count())
        Thread.sleep(80)
        assertEquals(0, tracker.count())
    }

    // ------------------------------------------------------------------ HTTP server

    private var server: LocalWebServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun start(handler: (HttpRequest) -> HttpResponse): Int {
        val s = LocalWebServer(handler)
        server = s
        return s.start(emptyList())
    }

    private fun request(port: Int, method: String, path: String, body: String? = null): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        return code to text
    }

    @Test
    fun server_passesMethodPathQueryAndUtf8Body() {
        val port = start { req ->
            HttpResponse.json(200, JSONObject()
                .put("method", req.method)
                .put("path", req.path)
                .put("q", req.query["q"])
                .put("body", req.body)
                .put("host", req.headers["host"])
                .toString())
        }
        val (code, text) = request(port, "PUT", "/api/orders/abc?q=a%20b&x=1", """{"name":"রহিম"}""")
        assertEquals(200, code)
        val json = JSONObject(text)
        assertEquals("PUT", json.getString("method"))
        assertEquals("/api/orders/abc", json.getString("path"))
        assertEquals("a b", json.getString("q"))
        assertEquals("""{"name":"রহিম"}""", json.getString("body"))
        assertEquals("127.0.0.1:$port", json.getString("host"))
    }

    @Test
    fun server_answersManyRequestsAndReportsHandlerErrors() {
        var calls = 0
        val port = start { req ->
            calls++
            if (req.path == "/boom") throw IllegalStateException("x")
            HttpResponse.text(200, "ok")
        }
        repeat(20) { assertEquals(200 to "ok", request(port, "GET", "/")) }
        assertEquals(500, request(port, "GET", "/boom").first)
        assertEquals(21, calls)
    }

    @Test
    fun server_rejectsOversizedBody() {
        val port = start { HttpResponse.text(200, "ok") }
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
            s.soTimeout = 5000
            s.getOutputStream().write(
                "POST /api/orders HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${LocalWebServer.MAX_BODY_BYTES + 1}\r\n\r\n".toByteArray()
            )
            val reply = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(reply, reply.startsWith("HTTP/1.1 413"))
        }
    }

    @Test
    fun server_rejectsGarbage() {
        val port = start { HttpResponse.text(200, "ok") }
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
            s.soTimeout = 5000
            s.getOutputStream().write("NONSENSE\r\n\r\n".toByteArray())
            val reply = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(reply, reply.startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun server_fallsBackWhenPreferredPortIsTaken() {
        val first = LocalWebServer { HttpResponse.text(200, "one") }
        val port = first.start(emptyList())
        try {
            val second = LocalWebServer { HttpResponse.text(200, "two") }
            server = second
            val other = second.start(listOf(port))
            assertTrue(other != port)
            assertEquals(200 to "two", request(other, "GET", "/"))
        } finally {
            first.stop()
        }
    }

    @Test
    fun server_stopInterruptsWaitingHandlersAndFreesPort() {
        val waiting = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val port = start {
            waiting.countDown()
            try {
                Thread.sleep(20_000)
            } catch (e: InterruptedException) {
                interrupted.countDown()
                throw e
            }
            HttpResponse.text(200, "late")
        }
        Thread { runCatching { request(port, "GET", "/api/state?since=1") } }.start()
        assertTrue(waiting.await(3, TimeUnit.SECONDS))
        server!!.stop()
        assertTrue(interrupted.await(3, TimeUnit.SECONDS))
        assertFalse(server!!.isRunning)

        // The port can be used again straight away.
        val again = LocalWebServer { HttpResponse.text(200, "again") }
        server = again
        assertEquals(port, again.start(listOf(port)))
    }

    @Test
    fun readRequest_parsesBareNewlinesToo() {
        val raw = "GET /x?a=1 HTTP/1.1\nHost: h\n\n"
        val req = LocalWebServer.readRequest(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("/x", req.path)
        assertEquals("1", req.query["a"])
        assertEquals("h", req.headers["host"])
        assertNull(LocalWebServer.readRequest(ByteArrayInputStream(ByteArray(0))))
    }
}
