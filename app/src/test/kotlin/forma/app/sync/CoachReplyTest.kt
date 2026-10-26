package forma.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The server's answer read: text and calls, or the reason there is none. */
class CoachReplyTest {
    @Test fun textAndCalls() {
        val r = CoachReply.parse(200, """{"text":"Ок","calls":[{"name":"remember","args":{"text":"x","why":"y"}},{"args":{}},{"name":"pause"}]}""")
        assertEquals("Ок", r.text)
        assertEquals(listOf("remember", "pause"), r.calls.map { it.name })
        assertEquals("\"x\"", r.calls.first().args["text"].toString())
        assertTrue(r.calls[1].args.isEmpty())
    }

    @Test fun callsWithoutText() {
        val r = CoachReply.parse(200, """{"text":"","calls":[{"name":"remember","args":{"text":"x"}}]}""")
        assertEquals("", r.text)
        assertEquals(1, r.calls.size)
    }

    @Test fun reasons() {
        fun reason(status: Int, body: String) = assertFailsWith<CoachException> { CoachReply.parse(status, body) }.reason
        assertEquals(CoachException.Reason.NOT_SIGNED_IN, reason(401, """{"error":"sign_in"}"""))
        assertEquals(CoachException.Reason.NOT_SET_UP, reason(503, """{"error":"no_key"}"""))
        assertEquals(CoachException.Reason.NOT_SET_UP, reason(404, "not found"))
        assertEquals(CoachException.Reason.BUSY, reason(502, """{"error":"busy","status":429}"""))
        assertEquals(CoachException.Reason.FAILED, reason(200, """{"text":"","calls":[]}"""))
        assertEquals(CoachException.Reason.FAILED, reason(502, "<html>"))
    }
}
