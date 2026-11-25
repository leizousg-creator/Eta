package io.github.mangi.eta.agent.approval

import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentApprovalBrokerTest {

    @After
    fun reset() {
        AgentApprovalBroker.resetForTest()
    }

    @Test(timeout = 10_000)
    fun approveWakesWaiter() {
        val answer = AtomicReference<AgentApprovalBroker.Answer>()
        val worker = thread {
            answer.set(AgentApprovalBroker.awaitDecision("run-1", "terminal", "{}", "执行命令", 5_000))
        }
        val request = awaitPending()
        assertEquals("run-1", request.runId)
        assertEquals("terminal", request.toolName)
        assertEquals("执行命令", request.risk)
        assertTrue(AgentApprovalBroker.answer(request.id, true))
        worker.join(5_000)
        assertEquals(AgentApprovalBroker.Answer.APPROVED, answer.get())
        assertNull(AgentApprovalBroker.pending.value)
    }

    @Test(timeout = 10_000)
    fun denyWakesWaiterHeadless() {
        val answer = AtomicReference<AgentApprovalBroker.Answer>()
        val worker = thread {
            answer.set(AgentApprovalBroker.awaitDecision("run-2", "set_setting", "{}", "改设置", 5_000))
        }
        val request = awaitPending()
        assertTrue(AgentApprovalBroker.answer(request.id, false))
        worker.join(5_000)
        assertEquals(AgentApprovalBroker.Answer.DENIED, answer.get())
    }

    @Test(timeout = 10_000)
    fun timeoutWithoutAnswer() {
        val answer = AtomicReference<AgentApprovalBroker.Answer>()
        val worker = thread {
            answer.set(AgentApprovalBroker.awaitDecision("run-3", "terminal", "{}", "执行命令", 300))
        }
        worker.join(5_000)
        assertEquals(AgentApprovalBroker.Answer.NOT_ANSWERED, answer.get())
    }

    @Test(timeout = 10_000)
    fun cancelRunReleasesWaiter() {
        val answer = AtomicReference<AgentApprovalBroker.Answer>()
        val worker = thread {
            answer.set(AgentApprovalBroker.awaitDecision("run-4", "terminal", "{}", "执行命令", 30_000))
        }
        awaitPending()
        AgentApprovalBroker.cancelRun("run-4")
        worker.join(5_000)
        assertEquals(AgentApprovalBroker.Answer.CANCELLED, answer.get())
    }

    @Test(timeout = 10_000)
    fun cancellationPredicateReleasesWaiter() {
        val answer = AtomicReference<AgentApprovalBroker.Answer>()
        val worker = thread {
            answer.set(
                AgentApprovalBroker.awaitDecision(
                    runId = "run-5",
                    toolName = "terminal",
                    argsPreview = "{}",
                    risk = "执行命令",
                    timeoutMillis = 30_000,
                    isCancelled = { true },
                ),
            )
        }
        worker.join(5_000)
        assertEquals(AgentApprovalBroker.Answer.CANCELLED, answer.get())
    }

    @Test
    fun answeringUnknownRequestIsIgnored() {
        assertFalse(AgentApprovalBroker.answer("nope", true))
    }

    @Test
    fun zeroTimeoutNeverRunsTool() {
        val answer = AgentApprovalBroker.awaitDecision("run-6", "terminal", "{}", "执行命令", 0)
        assertEquals(AgentApprovalBroker.Answer.NOT_ANSWERED, answer)
    }

    private fun awaitPending(): AgentApprovalBroker.Request {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            AgentApprovalBroker.pending.value?.let { return it }
            Thread.sleep(20)
        }
        fail("审批请求未出现在 pending 里")
        error("不可达")
    }
}
