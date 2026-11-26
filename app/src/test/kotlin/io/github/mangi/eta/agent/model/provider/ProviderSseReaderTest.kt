package io.github.mangi.eta.agent.model.provider

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSseReaderTest {
    @Test
    fun framingHandlesCommentsMultilineDataAndFinalFrameWithoutBlankLine() {
        val source = ": keepalive\r\n\r\nevent: delta\r\ndata: 第一行\r\ndata: 第二行\r\n\r\n" +
            "event: done\ndata: 终态"
        val events = mutableListOf<Pair<String, String>>()
        readProviderSse(source.byteInputStream(), AgentRunController()) { name, data ->
            events += name to data
            true
        }
        assertEquals(listOf("delta" to "第一行\n第二行", "done" to "终态"), events)
    }

    @Test
    fun terminalEventClosesStreamWithoutReadingAgain() {
        val stream = TrackedStream("data: completed\n\n", rejectEofRead = true)
        readProviderSse(stream, AgentRunController()) { _, data ->
            assertEquals("completed", data)
            false
        }
        assertTrue(stream.closed)
    }

    @Test
    fun cancellationStopsBufferedEventsAndClosesStream() {
        val stream = TrackedStream("data: first\n\ndata: second\n\n")
        val controller = AgentRunController()
        val received = mutableListOf<String>()
        val failure = runCatching {
            readProviderSse(stream, controller) { _, data ->
                received += data
                controller.cancel()
                true
            }
        }.exceptionOrNull()
        assertTrue(failure is AgentRunCancelledException)
        assertEquals(listOf("first"), received)
        assertTrue(stream.closed)
    }

    @Test
    fun consumerFailurePropagatesAndClosesStream() {
        val stream = TrackedStream("data: invalid\n\n")
        val expected = IllegalArgumentException("Invalid fixture")
        val failure = runCatching {
            readProviderSse(stream, AgentRunController()) { _, _ -> throw expected }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(stream.closed)
    }

    @Test(timeout = 5_000)
    fun idleWatchdogInterruptsHalfDeadStreamAndReportsTimedOut() {
        // 上游只发一行后既不结束也不再推进：看门狗应在超时后关流打断 readLine
        val stream = HangingStream("data: hello\n\n")
        val received = mutableListOf<String>()
        val outcome = readProviderSse(stream, AgentRunController(), idleTimeoutMs = 300L) { _, data ->
            received += data
            true
        }
        assertEquals(SseReadOutcome.TIMED_OUT, outcome)
        assertEquals(listOf("hello"), received)
        assertTrue(stream.closed)
    }

    /** 读完预置内容后一直阻塞，直到 close() 才返回 EOF。 */
    private class HangingStream(content: String) : java.io.InputStream() {
        private val bytes = content.toByteArray(Charsets.UTF_8)
        private var index = 0
        @Volatile var closed = false
            private set
        private val lock = Object()

        override fun read(): Int {
            if (index < bytes.size) return bytes[index++].toInt() and 0xff
            synchronized(lock) {
                while (!closed) lock.wait(50L)
            }
            return -1
        }

        override fun close() {
            closed = true
            synchronized(lock) { lock.notifyAll() }
            super.close()
        }
    }

    private class TrackedStream(
        content: String,
        private val rejectEofRead: Boolean = false,
    ) : ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)) {
        var closed = false
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!rejectEofRead || available() > 0) { "Reader waited beyond terminal event" }
            return super.read(buffer, offset, length)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
