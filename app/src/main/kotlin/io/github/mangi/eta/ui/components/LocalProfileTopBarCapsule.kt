package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.LocalToolProfile
import io.github.mangi.eta.agent.model.LocalToolProfileStore
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 顶栏「工具档」胶囊：点一下在五档之间切换，每档后面跟一句极简说明。
 * 档位决定发给模型的工具 schema 体量；纯聊天档完全不声明工具。
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
            val options = LocalToolProfile.values().toList()
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        item = DropdownItem(text = optionText(option)),
                        optionSize = options.size,
                        isSelected = option == profile,
                        index = index,
                        onSelectedIndexChange = {
                            LocalToolProfileStore.save(option, profileKey?.first, profileKey?.second)
                            profile = option
                            showMenu = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun optionText(profile: LocalToolProfile): String =
    stringResource(profileLabel(profile)) + " · " + stringResource(profileHint(profile))

internal fun profileLabel(profile: LocalToolProfile): Int = when (profile) {
    LocalToolProfile.FULL -> R.string.local_profile_full
    LocalToolProfile.CHAT -> R.string.local_profile_chat
    LocalToolProfile.LIGHT -> R.string.local_profile_light
    LocalToolProfile.STANDARD -> R.string.local_profile_standard
    LocalToolProfile.MINIMAL -> R.string.local_profile_minimal
}

private fun profileHint(profile: LocalToolProfile): Int = when (profile) {
    LocalToolProfile.FULL -> R.string.local_profile_hint_full
    LocalToolProfile.CHAT -> R.string.local_profile_hint_chat
    LocalToolProfile.LIGHT -> R.string.local_profile_hint_light
    LocalToolProfile.STANDARD -> R.string.local_profile_hint_standard
    LocalToolProfile.MINIMAL -> R.string.local_profile_hint_minimal
}
