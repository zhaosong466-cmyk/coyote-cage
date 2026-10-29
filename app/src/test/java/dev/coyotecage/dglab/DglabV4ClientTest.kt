package dev.coyotecage.dglab

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DglabV4ClientTest {
    @Test fun stopAndClearNeverWaitForAnUnresponsiveDevice() = runBlocking {
        val received = LinkedBlockingQueue<JSONObject>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"hello","clientId":"controller"}""")
                webSocket.send("""{"type":"client_attached","clientId":"app"}""")
                webSocket.send("""{"type":"message","data":{"t":"ev","ev":"devices.snapshot","devices":[{"slotId":"device","name":"test","type":"COYOTE_030"}]}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val data = JSONObject(text).getJSONObject("data")
                if (data.getString("m").startsWith("device.op")) received.add(data)
                // Deliberately never acknowledge any request, including clear.
            }
        }))
        server.start()
        val client = DglabV4Client()
        try {
            client.connect(server.url("/v4").toString().replaceFirst("http", "ws"))
            withTimeout(5000) { client.state.first { it.devices.isNotEmpty() } }
            withTimeout(1000) { client.emergencyStop() }
            val messages = List(3) {
                requireNotNull(received.poll(2, TimeUnit.SECONDS)) { "Missing stop command" }
            }
            assertEquals(listOf("device.op", "device.op", "device.op.clear"),
                messages.map { it.getString("m") })
            assertEquals(listOf(0, 1), messages.take(2).map { it.getJSONObject("data").getInt("c") })
            messages.take(2).forEach {
                assertEquals(7, it.getJSONObject("data").getInt("t"))
                assertEquals(0, it.getJSONObject("data").getInt("v"))
            }
            // A normal clear must not hold the operation lock waiting for a reply either.
            withTimeout(1000) { client.clear(0); client.zero(0) }
            assertEquals("device.op.clear", received.poll(2, TimeUnit.SECONDS)?.getString("m"))
            assertEquals(7, received.poll(2, TimeUnit.SECONDS)?.getJSONObject("data")?.getInt("t"))
        } finally {
            client.disconnect()
            server.shutdown()
        }
    }
}
