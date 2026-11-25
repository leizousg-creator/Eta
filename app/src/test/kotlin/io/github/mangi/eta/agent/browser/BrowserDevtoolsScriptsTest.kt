package io.github.mangi.eta.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些断言只覆盖「脚本能生成且形如预期」，不执行 JS——
 * 真正的执行行为要靠真机浏览器验证。
 */
class BrowserDevtoolsScriptsTest {

    @Test
    fun installScriptIsSelfContained() {
        val script = BrowserDevtoolsScripts.install()
        assertTrue(script.contains("__etaDevtools"))
        assertTrue(script.contains("installed"))
        // 幂等：重复注入直接返回
        assertTrue(script.contains("'already'"))
        // 脱敏链必须存在，否则凭据会原样进模型上下文
        assertTrue(script.contains("REDACTED"))
        assertTrue(script.contains("isSensitiveInput"))
    }

    @Test
    fun kotlinInterpolationDidNotLeak() {
        listOf(
            BrowserDevtoolsScripts.install(),
            BrowserDevtoolsScripts.consoleRead(10, 0),
            BrowserDevtoolsScripts.networkRead(10, 0),
            BrowserDevtoolsScripts.domSnapshot("#main", 50),
            BrowserDevtoolsScripts.queryAll("a", 10),
            BrowserDevtoolsScripts.evalStart("1 + 1", 1000),
            BrowserDevtoolsScripts.evalPoll(),
            BrowserDevtoolsScripts.probe(),
        ).forEach { script ->
            assertFalse("脚本里残留了未展开的 Kotlin 模板", script.contains("\${"))
        }
    }

    @Test
    fun limitsAreInlined() {
        assertTrue(BrowserDevtoolsScripts.consoleRead(37, 5).contains("37"))
        assertTrue(BrowserDevtoolsScripts.consoleRead(37, 5).contains("5"))
        assertTrue(BrowserDevtoolsScripts.domSnapshot(null, 123).contains("123"))
        assertTrue(BrowserDevtoolsScripts.queryAll(null, 45).contains("45"))
    }

    @Test
    fun selectorIsQuotedForJavascript() {
        // 引号和换行必须被转义，否则会截断脚本
        val script = BrowserDevtoolsScripts.domSnapshot("a[title=\"x\"]", 10)
        assertTrue(script.contains("\\\"x\\\""))
    }

    @Test
    fun evalCodeIsQuotedNotInterpolated() {
        val script = BrowserDevtoolsScripts.evalStart("alert(\"hi\")\nreturn 1", 500)
        assertTrue(script.contains("alert("))
        assertTrue(script.contains("\\n"))
        assertTrue(script.contains("500"))
    }
}
