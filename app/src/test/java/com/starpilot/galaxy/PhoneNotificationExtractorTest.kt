package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class PhoneNotificationExtractorTest {
    private val extractor = PhoneNotificationExtractor
    private fun message(sender: String?, text: String, time: Long = 1) = PhoneNotificationExtractor.Message(sender, text, time)

    @Test fun categories() {
        assertEquals("message", extractor.category("msg", false))
        assertEquals("call", extractor.category("call", false))
        assertEquals("call", extractor.category("missed_call", false))
        assertEquals("email", extractor.category("email", false))
        assertEquals("social", extractor.category("social", false))
        assertEquals("other", extractor.category(null, false))
        assertEquals("other", extractor.category("reminder", false))
        assertEquals("message", extractor.category(null, true))
        assertEquals("message", extractor.category("reminder", true))
        assertEquals("message", extractor.category("social", true))
        assertEquals("message", extractor.category("email", true))
    }
    @Test fun messagingStyleSendsOnlyTheNewestMessage() {
        val fields = extractor.extract("2 new messages", "fallback", null, null,
            listOf(message("Alice", "First", 1), message("Alice", "Second", 2)), null)!!
        assertEquals("Alice", fields.sender)
        assertEquals("Second", fields.text)
        assertEquals("message", fields.category)
    }
    @Test fun groupConversationsNameTheGroup() {
        val fields = extractor.extract("Family", null, null, "Family", listOf(message("Alice", "Dinner at 7")), "msg")!!
        assertEquals("Alice · Family", fields.sender)
        assertEquals("Alice", extractor.extract(null, null, null, "alice", listOf(message("Alice", "Hi")), "msg")!!.sender)
        assertEquals("Family", extractor.extract(null, null, null, "Family", listOf(message("", "Hi")), "msg")!!.sender)
    }
    @Test fun ownRepliesAreSkipped() {
        assertNull(extractor.extract("Alice", "Hi", null, null, listOf(message("Alice", "Hi", 1), message(null, "On my way", 2)), "msg"))
        assertNull(extractor.extract("Alice", "Hi", null, null, listOf(message("Me", "On my way")), "msg", selfName = "Me"))
    }
    @Test fun imageMessagesFallBackToNotificationText() {
        assertEquals("Photo", extractor.extract("Alice", "Photo", null, null, listOf(message("Alice", "")), "msg")!!.text)
    }
    @Test fun standardNotificationsUseTitleAndExpandedText() {
        val fields = extractor.extract("Bank", "Short", "The full expanded text", null, emptyList(), "email")!!
        assertEquals("Bank", fields.sender)
        assertEquals("The full expanded text", fields.text)
        assertEquals("email", fields.category)
        assertEquals("Short", extractor.extract("Bank", "Short", "", null, emptyList(), null)!!.text)
    }
    @Test fun blankSenderAndTextAreSkipped() {
        assertNull(extractor.extract("", " ", "\n", null, emptyList(), "msg"))
        assertNull(extractor.extract(null, null, null, null, emptyList(), null))
        assertNotNull(extractor.extract("", "Only text", null, null, emptyList(), null))
    }
    @Test fun cleaningStripsControlsAndRespectsLimits() {
        assertEquals("Hello there", extractor.clean("Hello\n\u0007‮there\t", 500))
        assertEquals(120, extractor.extract("a".repeat(300), "b".repeat(900), null, null, emptyList(), null)!!.sender.length)
        assertEquals(500, extractor.extract("a", "b".repeat(900), null, null, emptyList(), null)!!.text.length)
        // A surrogate pair is never split at the limit.
        assertEquals("ab", extractor.clean("ab😀", 3))
        assertEquals("ab😀", extractor.clean("ab😀c", 4))
    }
    @Test fun newMessagesHaveDistinctIdentities() {
        val first = extractor.extract(null, null, null, null, listOf(message("Alice", "ok", 1)), "msg")!!
        val again = extractor.extract(null, null, null, null, listOf(message("Alice", "ok", 1)), "msg")!!
        val later = extractor.extract(null, null, null, null, listOf(message("Alice", "ok", 2)), "msg")!!
        assertEquals(first.identity, again.identity)
        assertNotEquals(first.identity, later.identity)
    }
    @Test fun dedupeSuppressesRepostsWithinTheWindow() {
        val dedupe = PhoneNotificationDedupe(windowMs = 60_000)
        assertTrue(dedupe.fresh("k", "Alice:Hi", 0))
        assertFalse(dedupe.fresh("k", "Alice:Hi", 30_000))
        assertFalse(dedupe.fresh("k", "Alice:Hi", 80_000))
        assertTrue(dedupe.fresh("k", "Alice:Hello", 81_000))
        assertTrue(dedupe.fresh("other", "Alice:Hello", 81_000))
        assertTrue(dedupe.fresh("k", "Alice:Hello", 200_000))
    }
    @Test fun queueKeepsNewestAndExpiresOld() {
        assertEquals(1, PhoneNotificationQueue().apply { offer(PhoneNotification("k", "app", "", "A", "m", "message", 0)) }.expire(8_001))
        val queue = PhoneNotificationQueue(capacity = 8, maxAgeMs = 30_000)
        fun item(i: Int, at: Long) = PhoneNotification("k$i", "app", "App", "Alice", "m$i", "message", at)
        var dropped = 0
        for (i in 0 until 10) dropped += queue.offer(item(i, i * 1000L))
        assertEquals(2, dropped)
        assertEquals(8, queue.size)
        assertEquals("k2", queue.peek()!!.key)
        assertEquals(3, queue.expire(34_500))
        assertEquals("k5", queue.peek()!!.key)
        queue.remove(queue.peek()!!)
        assertEquals("k6", queue.peek()!!.key)
        queue.clear()
        assertNull(queue.peek())
    }
    @Test fun diagnosticsHoldNoContent() {
        var now = 1000L
        val diagnostics = PhoneNotificationDiagnostics { now }
        assertTrue(diagnostics.details(false, true).contains("Enabled: false"))
        assertTrue(diagnostics.details(true, true).contains("Last forwarded: never"))
        diagnostics.count("queued")
        diagnostics.delivered(accepted = true, enabled = true)
        now += 3000
        var text = diagnostics.details(true, true)
        assertTrue(text.contains("Last forwarded: 3 s ago"))
        assertTrue(text.contains("Last result: Accepted"))
        assertTrue(text.contains("queued 1"))
        diagnostics.delivered(accepted = false, enabled = true)
        assertTrue(diagnostics.details(true, true).contains("Last result: Filtered on comma"))
        diagnostics.delivered(accepted = false, enabled = false)
        assertTrue(diagnostics.details(true, true).contains("Last result: Comma switch off"))
        diagnostics.unreachable()
        assertTrue(diagnostics.details(true, true).contains("unreachable"))
        diagnostics.tooOld(404)
        text = diagnostics.details(true, true)
        assertTrue(text.contains("Comma too old (HTTP 404)"))
        assertTrue(text.contains("accepted 1"))
    }
}
