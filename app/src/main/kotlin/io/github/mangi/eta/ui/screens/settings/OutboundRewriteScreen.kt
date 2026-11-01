package io.github.mangi.eta.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.OutboundRewriteRule
import io.github.mangi.eta.data.model.OutboundRewriteSettings
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 出站改写设置：请求前替换词 + 截断自动续写。
 *
 * 这里改的只是递交给模型的文本。会话记录、界面气泡和工具结果都保持原文，
 * 所以关掉开关后没有任何副作用需要回收。
 */
@Composable
internal fun OutboundRewriteScreen(
    settings: OutboundRewriteSettings,
    onBack: () -> Unit,
    onUpdate: ((OutboundRewriteSettings) -> OutboundRewriteSettings) -> Unit,
) {
    var newFrom by remember { mutableStateOf("") }
    var newTo by remember { mutableStateOf("") }

    MiuixScaffold(title = "出站改写", onBack = onBack) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
                    .navigationBarsPadding()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = sidePadding, end = sidePadding),
                overscrollEffect = null,
            ) {
                item(key = "switch-title") { EtaPreferenceGroupTitle("总开关") }
                item(key = "switch-group") {
                    EtaPreferenceGroup(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    ) {
                        EtaSwitchPreference(
                            title = "启用出站词替换",
                            summary = "只改发给模型的文本，聊天里仍显示原样；会话记录与工具结果不受影响",
                            checked = settings.enabled,
                            onCheckedChange = { enabled -> onUpdate { it.copy(enabled = enabled) } },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaSwitchPreference(
                            title = "同时改写系统提示词",
                            summary = "关闭时只改写用户消息与助手历史；提示词包和技能正文里带刺的词会漏出去",
                            checked = settings.applyToSystem,
                            enabled = settings.enabled,
                            onCheckedChange = { value -> onUpdate { it.copy(applyToSystem = value) } },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaSwitchPreference(
                            title = "输出截断时自动续写",
                            summary = "模型因长度上限被截断且没有工具调用时，自动追加一轮「继续」，最多 ${settings.maxAutoContinues} 次",
                            checked = settings.autoContinueEnabled,
                            onCheckedChange = { value -> onUpdate { it.copy(autoContinueEnabled = value) } },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = "最多续写次数",
                            summary = "到达上限后仍按原样报错，不会无限续",
                            endActions = {
                                EtaTextButton(
                                    text = "−",
                                    onClick = {
                                        onUpdate {
                                            it.copy(maxAutoContinues = (it.maxAutoContinues - 1).coerceAtLeast(1))
                                        }
                                    },
                                )
                                EtaTextButton(
                                    text = "+",
                                    onClick = {
                                        onUpdate {
                                            it.copy(
                                                maxAutoContinues = (it.maxAutoContinues + 1)
                                                    .coerceAtMost(OutboundRewriteSettings.MAX_AUTO_CONTINUES),
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                }

                item(key = "rules-title") { EtaPreferenceGroupTitle("替换规则（${settings.rules.size} 条）") }
                item(key = "rules-group") {
                    EtaPreferenceGroup(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    ) {
                        if (settings.rules.isEmpty()) {
                            EtaPreference(
                                title = "还没有规则",
                                summary = "在下面添加一对词，例如「破解」→「逆向」",
                            )
                        } else {
                            settings.rules.forEachIndexed { index, rule ->
                                if (index > 0) EtaPreferenceDivider(hasLeading = false)
                                EtaPreference(
                                    title = rule.from,
                                    summary = if (rule.to.isEmpty()) "替换为空（等于删掉这个词）" else "替换为：${rule.to}",
                                    endActions = {
                                        EtaTextButton(
                                            text = "删除",
                                            onClick = {
                                                onUpdate { current ->
                                                    current.copy(
                                                        rules = current.rules.filterIndexed { i, _ -> i != index },
                                                    )
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                item(key = "add-title") { EtaPreferenceGroupTitle("新增规则") }
                item(key = "add-group") {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 24.dp),
                    ) {
                        TextField(
                            value = newFrom,
                            onValueChange = { newFrom = it },
                            label = "原词",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = newTo,
                            onValueChange = { newTo = it },
                            label = "替换为（留空表示删掉这个词）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        EtaTextButton(
                            text = "添加规则",
                            enabled = newFrom.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val from = newFrom.trim()
                                if (from.isNotEmpty()) {
                                    val rule = OutboundRewriteRule(from = from, to = newTo.trim())
                                    onUpdate { current ->
                                        current.copy(
                                            rules = (current.rules.filterNot { it.from == rule.from } + rule),
                                        )
                                    }
                                    newFrom = ""
                                    newTo = ""
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
