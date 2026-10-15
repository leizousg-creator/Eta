package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.LocalToolProfile
import io.github.mangi.eta.agent.model.LocalToolProfileStore
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 顶栏「工具档」胶囊：点一下切换档位。下拉里第一项是纯聊天（不带工具），
 * 后面按用途分两组——能干活（正常／开发）、省 token（轻度／标准／极简）；
 * 组标题是矮一行的小字＋一句介绍，档位行自绘，行距比原生紧凑。
 * 档位只决定发给模型的工具 schema 体量，不影响工具自身的执行能力。
 */
@Composable
internal fun LocalProfileTopBarCapsule(model: AgentModelOptionUi?) {
    val profileKey = model?.let { it.providerId to it.modelId }
    var profile by remember(profileKey) { mutableStateOf(LocalToolProfileStore.current(profileKey?.first, profileKey?.second)) }
    var showMenu by remember { mutableStateOf(false) }
    val name = stringResource(R.string.local_profile_capsule_name)
    Box {
        StatusCapsule(
            text = name + "·" + stringResource(profileLabel(profile)),
            modifier = Modifier.clickable { showMenu = true },
            description = stringResource(R.string.local_profile_capsule_description, name),
        )
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            ListPopupColumn {
                profileRows.forEach { row ->
                    val picked = row.profile
                    if (picked != null) {
                        OptionRow(
                            profile = picked,
                            selected = picked == profile,
                            onPick = {
                                LocalToolProfileStore.save(picked, profileKey?.first, profileKey?.second)
                                profile = picked
                                showMenu = false
                            },
                        )
                    } else {
                        GroupHeader(
                            title = row.groupTitle!!,
                            desc = row.groupDesc!!,
                            divider = row.divider,
                        )
                    }
                }
            }
        }
    }
}

/** 分组标题：小字标题＋一句介绍，半行高度；组间加细分隔线。 */
@Composable
private fun GroupHeader(title: Int, desc: Int, divider: Boolean) {
    Column(Modifier.widthIn(min = 176.dp)) {
        if (divider) {
            HorizontalDivider(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp))
        }
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 5.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(title),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(desc),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 档位行：自绘，纵向内边距比原生小，行与行之间不留额外缝隙。 */
@Composable
private fun OptionRow(profile: LocalToolProfile, selected: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(min = 176.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onPick)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(profileLabel(profile)),
            style = MiuixTheme.textStyles.body2,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(profileHint(profile)),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Text(
                text = "✓",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** 下拉里的一行：组标题（半行）或某个档位。 */
private data class ProfileRow(
    val groupTitle: Int? = null,
    val groupDesc: Int? = null,
    val profile: LocalToolProfile? = null,
    val divider: Boolean = false,
)

private val profileRows = listOf(
    ProfileRow(profile = LocalToolProfile.CHAT),
    ProfileRow(
        groupTitle = R.string.local_profile_group_work,
        groupDesc = R.string.local_profile_group_work_desc,
        divider = true,
    ),
    ProfileRow(profile = LocalToolProfile.FULL),
    ProfileRow(profile = LocalToolProfile.DEV),
    ProfileRow(
        groupTitle = R.string.local_profile_group_lean,
        groupDesc = R.string.local_profile_group_lean_desc,
        divider = true,
    ),
    ProfileRow(profile = LocalToolProfile.LIGHT),
    ProfileRow(profile = LocalToolProfile.STANDARD),
    ProfileRow(profile = LocalToolProfile.MINIMAL),
)

internal fun profileLabel(profile: LocalToolProfile): Int = when (profile) {
    LocalToolProfile.FULL -> R.string.local_profile_full
    LocalToolProfile.DEV -> R.string.local_profile_dev
    LocalToolProfile.CHAT -> R.string.local_profile_chat
    LocalToolProfile.LIGHT -> R.string.local_profile_light
    LocalToolProfile.STANDARD -> R.string.local_profile_standard
    LocalToolProfile.MINIMAL -> R.string.local_profile_minimal
}

private fun profileHint(profile: LocalToolProfile): Int = when (profile) {
    LocalToolProfile.FULL -> R.string.local_profile_hint_full
    LocalToolProfile.DEV -> R.string.local_profile_hint_dev
    LocalToolProfile.CHAT -> R.string.local_profile_hint_chat
    LocalToolProfile.LIGHT -> R.string.local_profile_hint_light
    LocalToolProfile.STANDARD -> R.string.local_profile_hint_standard
    LocalToolProfile.MINIMAL -> R.string.local_profile_hint_minimal
}
