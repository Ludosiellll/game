package forma.app.sync

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals

/**
 * The coach's answer takes as long as the model thinks: 20–46 s on a slow day (the function's log, 28.10). The phone
 * waits for it as long as it promises ([COACH_WAIT_MS]), not OkHttp's own 10 s of silence, and an answer that is late
 * is said as late, not as "no internet".
 */
class CoachHttpTest {
    private fun server(delayMs: Long, answer: String): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/functions/v1/coach") { ex ->
            ex.requestBody.readBytes()
            Thread.sleep(delayMs)
            val bytes = answer.toByteArray()
            ex.responseHeaders.add("content-type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @Test fun aSlowAnswerIsWaitedFor() {
        val s = server(12_000, """{"text":"Ответ"}""")
        try {
            val body = runBlocking {
                coachHttp().post("http://127.0.0.1:${s.address.port}/functions/v1/coach") { setBody("{}") }.bodyAsText()
            }
            assertEquals("""{"text":"Ответ"}""", body)
        } finally {
            s.stop(0)
        }
    }

    @Test fun lateIsNotOffline() {
        assertEquals(CoachException.Reason.SLOW, CoachException.reason(java.net.SocketTimeoutException("timeout")))
        assertEquals(CoachException.Reason.SLOW, CoachException.reason(io.ktor.client.network.sockets.SocketTimeoutException("Socket timeout has expired")))
        assertEquals(CoachException.Reason.SLOW, CoachException.reason(io.ktor.client.plugins.HttpRequestTimeoutException("u", COACH_WAIT_MS)))
        assertEquals(CoachException.Reason.OFFLINE, CoachException.reason(java.net.UnknownHostException("ftlnsroydkjbfrwyvamw.supabase.co")))
        assertEquals(CoachException.Reason.OFFLINE, CoachException.reason(java.net.ConnectException("refused")))
        assertEquals(CoachException.Reason.OFFLINE, CoachException.reason(io.ktor.client.network.sockets.ConnectTimeoutException("connect")))
        assertEquals(CoachException.Reason.FAILED, CoachException.reason(IllegalStateException("odd")))
    }
}
