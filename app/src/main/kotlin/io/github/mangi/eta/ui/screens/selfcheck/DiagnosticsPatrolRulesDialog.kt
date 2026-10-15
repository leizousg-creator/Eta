package io.github.mangi.eta.ui.screens.selfcheck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.DiagnosticsPatrolRules
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 自定义检测项的管理弹窗：列表 + 新增/编辑。
 *
 * 一条规则 = 名字 + 检测范围 + 关键字（一行一个，命中任一即算）+ 等级。
 * 存进 [DiagnosticsPatrolRules.FILE_PATH]，下次巡检直接生效，不用改源码重出包。
 */
@Composable
internal fun DiagnosticsPatrolRulesDialog(
    rules: List<DiagnosticsPatrolRules.Rule>,
    onRulesChange: (List<DiagnosticsPatrolRules.Rule>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var draft by remember { mutableStateOf<DiagnosticsPatrolRules.Rule?>(null) }
    var editIndex by remember { mutableStateOf(-1) }
    var nameInput by remember { mutableStateOf("") }
    var patternsInput by remember { mutableStateOf("") }
    var pathInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }

    fun startEdit(index: Int, rule: DiagnosticsPatrolRules.Rule) {
        editIndex = index
        nameInput = rule.name
        patternsInput = rule.patterns.joinToString("\n")
        pathInput = rule.path
        noteInput = rule.note
        draft = rule
    }

    fun commit(rule: DiagnosticsPatrolRules.Rule) {
        val updated = rules.toMutableList()
        if (editIndex in updated.indices) updated[editIndex] = rule else updated += rule
        onRulesChange(updated)
        draft = null
    }

    val current = draft
    EtaWindowDialog(
        show = true,
        title = if (current == null) {
            stringResource(R.string.diag_patrol_custom_title)
        } else if (editIndex in rules.indices) {
            stringResource(R.string.diag_patrol_custom_edit_title)
        } else {
            stringResource(R.string.diag_patrol_custom_add)
        },
        summary = if (current == null) stringResource(R.string.diag_patrol_custom_summary, rules.size) else null,
        onDismissRequest = { if (current == null) onDismiss() else draft = null },
    ) {
        if (current == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (rules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.diag_patrol_custom_empty),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    rules.forEachIndexed { index, rule ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        EtaArrowPreference(
                            title = rule.name,
                            summary = rule.summary(),
                            onClick = { startEdit(index, rule) },
                        )
                    }
                }
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = stringResource(R.string.diag_patrol_custom_copy_path),
                    summary = DiagnosticsPatrolRules.FILE_PATH,
                    onClick = {
                        copyRulesPath(context)
                        Toast.makeText(
                            context,
                            context.getString(R.string.diag_patrol_custom_copied_path),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.diag_patrol_custom_close),
                cancelText = stringResource(R.string.diag_patrol_custom_add),
                onCancel = { startEdit(-1, DiagnosticsPatrolRules.Rule()) },
                onConfirm = onDismiss,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = stringResource(R.string.diag_patrol_custom_name_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = patternsInput,
                    onValueChange = { patternsInput = it },
                    label = stringResource(R.string.diag_patrol_custom_patterns_label),
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp),
                )
                EtaSwitchPreference(
                    title = stringResource(R.string.diag_patrol_custom_regex),
                    summary = stringResource(R.string.diag_patrol_custom_regex_summary),
                    checked = current.regex,
                    onCheckedChange = { draft = current.copy(regex = it) },
                )
                EtaArrowPreference(
                    title = stringResource(R.string.diag_patrol_custom_target),
                    summary = current.target.label + "｜" + current.target.hint,
                    onClick = { draft = current.copy(target = DiagnosticsPatrolRules.Target.next(current.target)) },
                )
                if (current.target == DiagnosticsPatrolRules.Target.FILE) {
                    TextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        label = stringResource(R.string.diag_patrol_custom_path_label),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EtaArrowPreference(
                    title = stringResource(R.string.diag_patrol_custom_level),
                    summary = current.level.label + "｜" + stringResource(R.string.diag_patrol_custom_level_hint),
                    onClick = { draft = current.copy(level = DiagnosticsPatrolRules.RuleLevel.next(current.level)) },
                )
                EtaSwitchPreference(
                    title = stringResource(R.string.diag_patrol_custom_enabled),
                    checked = current.enabled,
                    onCheckedChange = { draft = current.copy(enabled = it) },
                )
                TextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = stringResource(R.string.diag_patrol_custom_note_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editIndex in rules.indices) {
                    EtaTextButton(
                        text = stringResource(R.string.diag_patrol_custom_delete),
                        onClick = {
                            val updated = rules.toMutableList()
                            updated.removeAt(editIndex)
                            onRulesChange(updated)
                            draft = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.diag_patrol_custom_save),
                cancelText = stringResource(R.string.action_cancel),
                onCancel = { draft = null },
                onConfirm = {
                    val rule = current.copy(
                        name = nameInput.trim().ifBlank { patternsInput.lineSequence().firstOrNull().orEmpty().trim() },
                        patterns = patternsInput.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                        path = pathInput.trim(),
                        note = noteInput.trim(),
                    )
                    if (rule.name.isBlank() || rule.patterns.isEmpty()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.diag_patrol_custom_invalid),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        commit(rule)
                    }
                },
            )
        }
    }
}

/** 复制规则文件路径，便于用文件管理器直接手改这份 JSON。 */
private fun copyRulesPath(context: Context) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("eta-patrol-rules", DiagnosticsPatrolRules.FILE_PATH))
}
