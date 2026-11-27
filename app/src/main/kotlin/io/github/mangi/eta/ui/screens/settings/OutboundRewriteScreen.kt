package io.github.mangi.eta.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.OutboundRewriteRule
import io.github.mangi.eta.data.model.OutboundRewriteSettings
import io.github.mangi.eta.data.repository.AgentOutboundRepository
import io.github.mangi.eta.ui.components.ScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 出站改写设置（fork）。
 *
 * 这里改的只是递交给模型的文本。会话记录、界面气泡和工具结果都保持原文，
 * 所以关掉开关后没有任何副作用需要回收。
 *
 * 截断自动续写不在这一页：它要动主循环，单独一项加。
 */
@Composable
internal fun OutboundRewriteScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by AgentOutboundRepository.stateFlow.collectAsState()
    val fromState = remember { TextFieldState("") }
    val toState = remember { TextFieldState("") }
    var notice by remember { mutableStateOf<String?>(null) }

    fun update(transform: (OutboundRewriteSettings) -> OutboundRewriteSettings) {
        scope.launch { AgentOutboundRepository.update(transform) }
    }

    ScaffoldPage(
        title = "出站改写",
        onBack = onBack,
    ) {
        item(key = "ob_status_title") { SmallTitle("状态") }
        item(key = "ob_status") {
            ObCard {
                BasicComponent(
                    title = if (settings.enabled) "已启用" else "未启用",
                    summary = if (settings.enabled) {
                        "发送前按 ${settings.activeRules.size} 条规则改写正文；聊天里仍显示原样，工具结果不受影响"
                    } else {
                        "当前不改写，请求按原文发出"
                    },
                )
            }
        }

        item(key = "ob_switch_title") { SmallTitle("开关") }
        item(key = "ob_switch") {
            ObCard {
                BasicComponent(
                    title = "启用出站词替换",
                    summary = "只改发给模型的文本，聊天里仍显示原样；会话记录与工具结果不受影响；点一下切换",
                    onClick = { update { it.copy(enabled = !it.enabled) } },
                    endActions = {
                        Switch(
                            checked = settings.enabled,
                            onCheckedChange = { value -> update { it.copy(enabled = value) } },
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    },
                )
                BasicComponent(
                    title = "同时改写系统提示词",
                    summary = "关闭时只改写用户消息与助手历史；提示词包和技能正文里带刺的词会漏出去",
                    enabled = settings.enabled,
                    onClick = { update { it.copy(applyToSystem = !it.applyToSystem) } },
                    endActions = {
                        Switch(
                            checked = settings.applyToSystem,
                            onCheckedChange = { value -> update { it.copy(applyToSystem = value) } },
                            enabled = settings.enabled,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    },
                )
            }
        }

        item(key = "ob_rules_title") { SmallTitle("替换规则（${settings.rules.size} 条）") }
        item(key = "ob_rules") {
            ObCard {
                if (settings.rules.isEmpty()) {
                    BasicComponent(
                        title = "还没有规则",
                        summary = "在下面添加一对词，例如「破解」→「逆向」",
                    )
                } else {
                    settings.rules.forEachIndexed { index, rule ->
                        BasicComponent(
                            title = rule.from,
                            summary = if (rule.to.isEmpty()) {
                                "替换为空（等于删掉这个词）"
                            } else {
                                "替换为：${rule.to}"
                            },
                            endActions = {
                                Text(
                                    text = "删除",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .clickable {
                                            update { current ->
                                                current.copy(
                                                    rules = current.rules.filterIndexed { i, _ -> i != index },
                                                )
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            },
                        )
                    }
                }
            }
        }

        item(key = "ob_add_title") { SmallTitle("新增规则") }
        item(key = "ob_add_from") {
            GlassTextField(
                state = fromState,
                label = "原词",
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
            )
        }
        item(key = "ob_add_to") {
            GlassTextField(
                state = toState,
                label = "替换为（留空表示删掉这个词）",
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
            )
        }
        item(key = "ob_add_action") {
            ObCard {
                BasicComponent(
                    title = "添加规则",
                    summary = notice ?: "同名词再次添加会覆盖原规则；最多 ${OutboundRewriteSettings.MAX_RULES} 条",
                    onClick = {
                        val from = fromState.text.toString().trim()
                        val to = toState.text.toString().trim()
                        if (from.isEmpty()) {
                            notice = "原词不能为空"
                        } else if (from == to) {
                            notice = "原词和替换词相同，这条规则不会生效"
                        } else {
                            val rule = OutboundRewriteRule(from = from, to = to)
                            update { current ->
                                current.copy(rules = current.rules.filterNot { it.from == rule.from } + rule)
                            }
                            fromState.setTextAndPlaceCursorAtEnd("")
                            toState.setTextAndPlaceCursorAtEnd("")
                            notice = "已添加「${rule.from}」→「${rule.to.ifEmpty { "（删除）" }}」"
                        }
                    },
                )
            }
        }

        item(key = "ob_note_title") { SmallTitle("说明") }
        item(key = "ob_note") {
            ObCard {
                BasicComponent(
                    title = "作用范围",
                    summary = "只改 user / assistant（可选 system）的正文；工具调用参数与工具返回值保持原样，" +
                        "否则命令、路径和代码会被改坏",
                )
                BasicComponent(
                    title = "留着不用也没关系",
                    summary = "关闭时零开销：不会扫描历史，也不会生成新数组",
                )
            }
        }
    }
}

@Composable
private fun ObCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        content()
    }
}
