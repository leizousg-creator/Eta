package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.LocalToolProfile
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import io.github.mangi.eta.ui.model.latestContextUsage
import io.github.mangi.eta.ui.model.TokenUsageUi
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConversationStatusCapsules(
    messages: List<AgentChatMessageUi>,
    model: AgentModelOptionUi?,
    lastUsage: TokenUsageUi?,
    trimmed: Pair<Int, Int>? = null,
    downgradedProfileId: String? = null,
    compaction: Pair<Int, Int>? = null,
) {
    val usage = latestContextUsage(messages, model).let { it.copy(contextTokens = lastUsage?.contextTokens ?: it.contextTokens) }
    val count = usage.contextTokens?.let { formatCompactTokenCount(it) } ?: "—"
    val limit = usage.contextWindow?.takeIf { it > 0 }?.let { formatCompactTokenCount(it) }
    val percent = usage.contextWindow?.takeIf { it > 0 }?.let { window ->
        usage.contextTokens?.takeIf { it >= 0 }?.let { tokens ->
            NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(tokens.toDouble() / window)
        }
    }
    val compactionText = compaction?.let { (rounds, max) ->
        " · " + stringResource(R.string.conversation_compaction_round_capsule, rounds, max)
    } ?: ""
    val contextText = count + (limit?.let { "/$it" } ?: "") + (percent?.let { " · $it" } ?: "") + compactionText
    val contextDescription = stringResource(R.string.conversation_context_capsule) + " " + contextText
    val speed = (lastUsage ?: messages.asReversed().filterIsInstance<AgentMessageUi>()
        .firstOrNull { it.usage != null }?.usage)?.averageTokensPerSecond
    val speedText = speed?.let {
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it)
    } ?: "—"
    Row(Modifier.widthIn(max = 420.dp).padding(start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusCapsule(contextText, Modifier.weight(1f, fill = false), contextDescription)
        MemoryUsageCapsule()
        LocalProfileTopBarCapsule(model)
        trimmed?.let { (rounds, count) ->
            StatusCapsule(
                stringResource(R.string.conversation_trimmed_capsule, count), Modifier,
                stringResource(R.string.conversation_trimmed_description, rounds),
            )
        }
        downgradedProfileId?.let { id ->
            val label = stringResource(profileLabel(LocalToolProfile.fromId(id)))
            StatusCapsule(
                stringResource(R.string.conversation_downgrade_capsule, label), Modifier,
                stringResource(R.string.conversation_downgrade_description, label),
            )
        }
    }
}

/** 输入栏左侧的上下文用量文字胶囊：`125.67K/200K · 62.8%`。 */
@Composable
internal fun AgentContextUsageCapsule(
    usage: AgentContextUsageUi,
    modifier: Modifier = Modifier,
) {
    val tokens = usage.contextTokens ?: return
    val count = formatCompactTokenCount(tokens)
    val limit = usage.contextWindow?.takeIf { it > 0 }?.let { formatCompactTokenCount(it) }
    val percent = usage.contextWindow?.takeIf { it > 0 }?.let { window ->
        NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
            .format(tokens.toDouble() / window)
    }
    val text = count + (limit?.let { "/$it" } ?: "") + (percent?.let { " · $it" } ?: "")
    Text(
        text = text,
        modifier = modifier
            .background(MiuixTheme.colorScheme.surfaceContainer, CircleShape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1,
        fontSize = 11.sp,
    )
}

@Composable
internal fun StatusCapsule(text: String, modifier: Modifier, description: String = text) {
    Text(text,
        modifier = modifier.background(MiuixTheme.colorScheme.surfaceContainer, CircleShape)
            .semantics { contentDescription = description }.padding(horizontal = 8.dp, vertical = 5.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1, fontSize = 11.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
