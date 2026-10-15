package io.github.mangi.eta.ui.screens.selfcheck

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.core.CrashRecorder
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ChunkedTextDao
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.TextStoreMaintenance
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STATE_OK = "✔ 正常"
private const val STATE_MISSING = "✘ 待处理"
private const val STATE_BUILTIN = "● 已内置"

/** 单项自检结果：权限类项目带一个可跳转的 uri，方便直接去修。 */
private data class SelfCheckItem(
    val name: String,
    val state: String,
    val detail: String,
    val actionUri: String? = null,
)

private data class SelfCheckReport(
    val permissions: List<SelfCheckItem>,
    val storage: List<SelfCheckItem>,
    val builtin: List<SelfCheckItem>,
) {
    val total: Int get() = permissions.size + storage.size + builtin.size
    val okCount: Int get() = (permissions + storage).count { it.state == STATE_OK }
    val problemCount: Int get() = (permissions + storage).count { it.state != STATE_OK }
}

/** 设置 → 关于 → 功能自检：一键把已开发功能的运行条件与内置状态过一遍。 */
@Composable
internal fun FeatureSelfCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var report by remember { mutableStateOf<SelfCheckReport?>(null) }
    var round by remember { mutableStateOf(0) }
    // 63 项一屏铺开太长：三组各自可折叠，③ 内置功能默认收起。
    var showPermissions by remember { mutableStateOf(true) }
    var showStorage by remember { mutableStateOf(true) }
    var showBuiltin by remember { mutableStateOf(false) }
    LaunchedEffect(round) {
        report = withContext(Dispatchers.IO) { runSelfCheck(context) }
    }

    MiuixScaffoldPage(title = "功能自检", onBack = onBack) {
        item(key = "selfcheck-head") {
            val current = report
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "重新检测",
                    summary = when {
                        current == null -> "正在检测…"
                        current.problemCount == 0 -> "共 ${current.total} 项，运行条件全部就绪"
                        else -> "共 ${current.total} 项，有 ${current.problemCount} 项待处理（点条目可跳转处理）"
                    },
                    onClick = { round += 1 },
                )
            }
        }

        item(key = "selfcheck-permissions-head") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "① 权限与环境",
                    summary = if (showPermissions) "点一下收起" else "已收起，点一下展开（${report?.permissions?.size ?: 0} 项）",
                    onClick = { showPermissions = !showPermissions },
                )
            }
        }

        if (showPermissions) item(key = "selfcheck-permissions") {
            EtaPreferenceGroup {
                val items = report?.permissions.orEmpty()
                if (items.isEmpty()) {
                    EtaPreference(title = "读取中", summary = "…")
                } else {
                    items.forEachIndexed { index, item ->
                        if (index > 0) EtaPreferenceDivider()
                        if (item.actionUri != null) {
                            EtaArrowPreference(
                                title = "${item.state}  ${item.name}",
                                summary = item.detail,
                                onClick = { openUri(context, item.actionUri) },
                            )
                        } else {
                            EtaPreference(title = "${item.state}  ${item.name}", summary = item.detail)
                        }
                    }
                }
            }
        }

        item(key = "selfcheck-storage-head") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "② 数据与存储",
                    summary = if (showStorage) "点一下收起" else "已收起，点一下展开（${report?.storage?.size ?: 0} 项）",
                    onClick = { showStorage = !showStorage },
                )
            }
        }

        if (showStorage) item(key = "selfcheck-storage") {
            EtaPreferenceGroup {
                val items = report?.storage.orEmpty()
                if (items.isEmpty()) {
                    EtaPreference(title = "读取中", summary = "…")
                } else {
                    items.forEachIndexed { index, item ->
                        if (index > 0) EtaPreferenceDivider()
                        EtaPreference(title = "${item.state}  ${item.name}", summary = item.detail)
                    }
                }
            }
        }

        item(key = "selfcheck-textstore-trim") {
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = if (busy) "正在瘦身…" else "文本库瘦身",
                    summary = "把超上限的历史字段按同一条规则保尾截断并重写引用头；只动文本分块，不碰会话、消息与设置",
                    onClick = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val done = withContext(Dispatchers.IO) {
                                    runCatching { TextStoreMaintenance.trimOversized(EtaDatabase.get(context)) }.getOrNull()
                                }
                                busy = false
                                android.widget.Toast.makeText(
                                    context,
                                    done?.let { "瘦身完成：${it.trimmedFields} 个字段 · 省 ${it.savedChars} 字符" } ?: "瘦身失败",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                round += 1
                            }
                        }
                    },
                )
            }
        }

        item(key = "selfcheck-builtin-head") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "③ 已内置功能（本包确认存在）",
                    summary = if (showBuiltin) "点一下收起" else "已收起，点一下展开（${report?.builtin?.size ?: 0} 项）",
                    onClick = { showBuiltin = !showBuiltin },
                )
            }
        }

        if (showBuiltin) item(key = "selfcheck-builtin") {
            EtaPreferenceGroup {
                val items = report?.builtin.orEmpty()
                if (items.isEmpty()) {
                    EtaPreference(title = "读取中", summary = "…")
                } else {
                    items.forEachIndexed { index, item ->
                        if (index > 0) EtaPreferenceDivider()
                        EtaPreference(title = "${item.state}  ${item.name}", summary = item.detail)
                    }
                }
            }
        }
    }
}

private fun openUri(context: Context, uri: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private suspend fun runSelfCheck(context: Context): SelfCheckReport {
    val pkg = context.packageName
    val resolver = context.contentResolver
    val overlayOk = AndroidSettings.canDrawOverlays(context)
    val notifyOk = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
        PackageManager.PERMISSION_GRANTED
    val listeners = AndroidSettings.Secure.getString(
        resolver,
        "enabled_notification_listeners",
    ).orEmpty()
    // 优先问系统官方接口：部分 ROM 不刷新 enabled_notification_listeners，只看字符串会误报"没给权限"。
    val listenerOk = listeners.contains(pkg) || runCatching {
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(
                android.content.ComponentName(pkg, "$pkg.agent.device.AgentNotificationHistoryService"),
            ) == true
    }.getOrDefault(false)
    val a11y = AndroidSettings.Secure.getString(
        resolver,
        "enabled_accessibility_services",
    ).orEmpty()
    val a11yOk = a11y.contains(pkg)
    val suPath = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
    ).firstOrNull { File(it).exists() }

    val permissions = listOf(
        SelfCheckItem(
            name = "悬浮窗权限",
            state = if (overlayOk) STATE_OK else STATE_MISSING,
            detail = if (overlayOk) {
                "悬浮胶囊、虚拟屏小窗都能显示"
            } else {
                "缺这个：悬浮胶囊/小窗不会出现。去 应用详情 → 显示在其他应用上层"
            },
            actionUri = if (overlayOk) null else "package:$pkg",
        ),
        SelfCheckItem(
            name = "通知权限",
            state = if (notifyOk) STATE_OK else STATE_MISSING,
            detail = if (notifyOk) "可以发运行状态与完成提醒" else "缺这个：任务完成提醒发不出来",
            actionUri = if (notifyOk) null else "package:$pkg",
        ),
        SelfCheckItem(
            name = "通知使用权",
            state = if (listenerOk) STATE_OK else STATE_MISSING,
            detail = if (listenerOk) {
                "通知历史可用（通知触发任务、近 7 天记录）"
            } else {
                "未开：通知触发类任务与通知历史会缺失"
            },
            actionUri = if (listenerOk) null else "package:$pkg",
        ),
        SelfCheckItem(
            name = "无障碍服务",
            state = if (a11yOk) STATE_OK else STATE_MISSING,
            detail = if (a11yOk) {
                "界面操作（点击/输入/观察）与虚拟屏控制可用"
            } else {
                "未开：所有界面自动化都会失败（不是 Eta 的 bug，是系统没授权）"
            },
            actionUri = if (a11yOk) null else "package:$pkg",
        ),
        SelfCheckItem(
            name = "Root 权限",
            state = if (suPath != null) STATE_OK else STATE_MISSING,
            detail = if (suPath != null) "su 存在：$suPath（虚拟屏、文件与终端特权可用）" else "未发现 su，虚拟屏与特权功能会受限",
        ),
    )

    val storage = mutableListOf<SelfCheckItem>()
    val shareDir = File("/storage/emulated/0/ai共享文件夹")
    val shareOk = shareDir.isDirectory && shareDir.canWrite()
    storage += SelfCheckItem(
        name = "共享目录",
        state = if (shareOk) STATE_OK else STATE_MISSING,
        detail = if (shareOk) "/storage/emulated/0/ai共享文件夹 可读写" else "共享目录不存在或不可写",
    )

    val database = runCatching { EtaDatabase.get(context) }.getOrNull()
    val dbVersion = runCatching { database?.openHelper?.readableDatabase?.version }.getOrNull()
    val conversations = runCatching { database?.conversationDao()?.conversationCount() }.getOrNull()
    val messages = runCatching { database?.conversationDao()?.messageCount() }.getOrNull()
    val usageRecords = runCatching { database?.tokenUsageDao()?.recordCount() }.getOrNull()
    storage += SelfCheckItem(
        name = "数据库",
        state = if (dbVersion != null) STATE_OK else STATE_MISSING,
        detail = if (dbVersion != null) {
            "结构版本 v$dbVersion（本包预期 28）· 会话 ${conversations ?: 0} · 消息 ${messages ?: 0} · 用量 ${usageRecords ?: 0} 条"
        } else {
            "打不开本地数据库"
        },
    )
    val crashCount = runCatching { CrashRecorder.records(context).size }.getOrDefault(0)
    storage += SelfCheckItem(
        name = "崩溃留证",
        state = STATE_OK,
        detail = if (crashCount == 0) "暂无崩溃记录（正常）" else "已留 ${crashCount} 份，设置 → 关于可查看导出",
    )
    val textStore = runCatching { database?.let { TextStoreMaintenance.inspect(it) } }.getOrNull()
    storage += SelfCheckItem(
        name = "文本库体量",
        state = if (textStore == null || textStore.oversizedFields == 0) STATE_OK else STATE_MISSING,
        detail = if (textStore == null) {
            "读不到分块表"
        } else {
            val cap = ChunkedTextDao.MAX_FIELD_CHARS / 10000
            val window = ChunkedTextDao.MAX_RESTORE_CHARS / 10000
            val top = textStore.topFields.firstOrNull()?.let { " · 最大 ${it.field} ${it.charCount / 10000} 万字符" }.orEmpty()
            "分块 ${textStore.scannedChars / 10000} 万字符 / ${textStore.scannedFields} 个字段 · 单字段上限 ${cap} 万 · 单次读入窗口 ${window} 万 · 越线 ${textStore.oversizedFields} 个$top"
        },
    )
    val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
    val backupDetail = if (settings == null) {
        "读不到设置"
    } else if (!settings.autoBackupEnabled) {
        "未开启（设置 → 备份与恢复 里可开）"
    } else {
        val ts = settings.autoBackupLastAt
        val last = if (ts > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        } else {
            "尚未执行"
        }
        run {
            val minutes = io.github.mangi.eta.data.model.AutoBackupInterval.effectiveMinutes(
                settings.autoBackupIntervalMinutes,
                settings.autoBackupIntervalHours,
            )
            val period = if (minutes % 60 == 0) "每 ${minutes / 60} 小时" else "每 $minutes 分钟"
            val keep = io.github.mangi.eta.data.model.AutoBackupKeepCount.normalize(
                settings.autoBackupKeepCount,
            )
            // 把这两轮新做的备份能力并进同一条：最近一个全量包多大、数据库到底进没进包。
            val packInfo = runCatching {
                io.github.mangi.eta.data.repository.AutoBackupRunner
                    .backups(settings.autoBackupDirectory)
                    .firstOrNull { io.github.mangi.eta.data.repository.EtaFullBackup.isFullBackup(it.name) }
                    ?.let { file ->
                        val mb = String.format(java.util.Locale.US, "%.1f", file.length() / 1048576.0)
                        val hasDb = java.util.zip.ZipFile(file).use { zip ->
                            zip.getEntry("private/databases/eta.db") != null
                        }
                        " · 最新全量包 $mb MB · 数据库${if (hasDb) "已含" else "缺失"}"
                    }
            }.getOrNull().orEmpty()
            "已开启 · $period · 保留 $keep 份 · 上次 $last$packInfo"
        }
    }
    storage += SelfCheckItem(
        name = "定时自动备份",
        state = if (settings?.autoBackupEnabled == true) STATE_OK else STATE_MISSING,
        detail = backupDetail,
    )

    // 诊断包每周自动导出：清单里注册了接收器才可能跑；顺便报出自动目录里已有几份。
    val autoExportReady = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getReceiverInfo(
            android.content.ComponentName(
                context,
                "io.github.mangi.eta.core.DiagnosticsAutoExportReceiver",
            ),
            0,
        )
    }.isSuccess
    val autoCount = runCatching {
        File("/storage/emulated/0/ai共享文件夹/诊断包备份/自动")
            .listFiles()?.count { it.name.endsWith(".zip") } ?: 0
    }.getOrDefault(0)
    storage += SelfCheckItem(
        name = "诊断包自动导出",
        state = if (autoExportReady) STATE_OK else STATE_MISSING,
        detail = if (autoExportReady) {
            "接收器已注册 · 每周一次 · 落到 诊断包备份/自动（只留最近 4 份）· 该目录现有 $autoCount 份"
        } else {
            "接收器未注册，自动导出不会跑"
        },
    )

    val builtin = listOf(
        SelfCheckItem(
            "通知使用权判定加固",
            STATE_BUILTIN,
            "自检先问系统官方接口（isNotificationListenerAccessGranted）再回退读设置值：部分 ROM 不刷新 enabled_notification_listeners，只读字符串会把「已经给了权限」误报成「没给」",
        ),
        SelfCheckItem(
            "只留自定义模型",
            STATE_BUILTIN,
            "定制版不收预置模型提供商：启动时清掉没填 Key 的预置项；填过 Key 的（例如你手填过 Key 的 DeepSeek）自动转成自定义项保留，不会连凭据一起删",
        ),
        SelfCheckItem(
            "输入草稿按会话落盘",
            STATE_BUILTIN,
            "输入框每敲一个字就落盘（eta_chat_drafts）；所有进会话的入口统一贴回草稿（角色扮演开局、删除后接手、切会话都算），进会话以盘上内容为准；消息发出去即清草稿",
        ),
        SelfCheckItem("多会话并发", STATE_BUILTIN, "最多 3 个会话同时跑任务；悬浮胶囊列出运行中会话，点一条跳回"),
        SelfCheckItem("工具串行锁", STATE_BUILTIN, "点按/输入/终端等独占工具全局排队，只读工具仍并行"),
        SelfCheckItem("界面重连文本重复修复", STATE_BUILTIN, "切出去再回来，同一段回复不再重复 2~3 遍"),
        SelfCheckItem("虚拟屏与顶层浮动", STATE_BUILTIN, "独立 display 执行 GUI 任务；小窗可拖拽/贴边折叠/两档缩放"),
        SelfCheckItem("内存占用胶囊", STATE_BUILTIN, "顶栏显示内存占用，点开看明细"),
        SelfCheckItem("插话按会话投递", STATE_BUILTIN, "运行中插话只投给同会话正在跑的 run，别的会话在跑长任务时不会再被吸进去"),
        SelfCheckItem("顶栏状态行右靠紧凑", STATE_BUILTIN, "顶栏状态胶囊、新建对话、更多一起贴右，行尾留白收紧"),
        SelfCheckItem("文本库容量上限", STATE_BUILTIN, "单字段超 1200 万字符时保留尾部（尽量落在 JSON 元素边界），读入峰值约 50 MB，512 MB 堆内安全；超大堆由 largeHeap 提供"),
        SelfCheckItem("文本库瘦身体检", STATE_BUILTIN, "自检页「文本库体量」报出分块总量、最大字段、单次读入窗口与越线数，可一键瘦身；升级后首次起动自动跑一次"),
        SelfCheckItem("瘦身写回引用头", STATE_BUILTIN, "瘦身只保留尾部、按 JSON 元素边界切齐，删旧分块后把新引用头写回原列，读取不会 length mismatch"),
        SelfCheckItem("journal 与 history 去重", STATE_BUILTIN, "两个字段逐字相同时只存一份，读取回落到 history；同一段历史不再占两份体积"),
        SelfCheckItem("单次读入窗口", STATE_BUILTIN, "上下文读取按 200 万字符窗口取尾部，超长存档不再整串进堆；存储上限仍是单字段 1200 万字符"),
        SelfCheckItem("图片预览浮层", STATE_BUILTIN, "会话内点图放大预览（JPEG 92、最长边 1920、上限 3.69M 像素）"),
        SelfCheckItem("Linux 能力探测", STATE_BUILTIN, "终端环境页按实际能力显示可用项"),
        SelfCheckItem("离线朗读", STATE_BUILTIN, "系统 TTS 离线档位，设置 → 语音朗读 可试听"),
        SelfCheckItem("会话分类与自动任务", STATE_BUILTIN, "抽屉一级「自动任务」「会话分类」都在"),
        SelfCheckItem("用量统计 / 诊断包 / 环境包快照", STATE_BUILTIN, "统计 30 天用量、导出诊断包、整机快照一键还原"),
        SelfCheckItem(
            "无障碍断连提醒",
            STATE_BUILTIN,
            "服务被解绑或中断时，8 秒确认后发通知说明原因，点通知直达无障碍设置；重连自动撤销，5 分钟内不重复提醒",
        ),
        SelfCheckItem(
            "运行进度通知",
            STATE_BUILTIN,
            "跑任务时通知栏显示经过时间与当前动作；并发归 0 立刻撤销，不留残条",
        ),
        SelfCheckItem(
            "自动任务重试与立刻重试",
            STATE_BUILTIN,
            "失败先自动重试（通知写明第几次、约多久后再试、原因），可点「立刻重试」提前；只有不再重试才发失败通知",
        ),
        SelfCheckItem(
            "内存闸门与降档说明",
            STATE_BUILTIN,
            "内存紧张时新会话排队或冻结；并发上限被压低时进度通知里写明「内存紧张，并发上限 N/6」",
        ),
        SelfCheckItem(
            "行为核查与账目",
            STATE_BUILTIN,
            "行为核查含十多项探针，并给出账目：开始/结束、失败率、平均会话时长、重试与收场次数",
        ),
        SelfCheckItem(
            "诊断包按天与分享",
            STATE_BUILTIN,
            "诊断包含 7 天按天日志；设置 → 关于 可导出到「诊断包备份」或直接分享",
        ),
        SelfCheckItem(
            "中断续跑横幅",
            STATE_BUILTIN,
            "被中断的对话回合给出「继续 / 忽略」，并写明上次停在哪一步、有没有动过手机（当时做了什么动作）",
        ),
        SelfCheckItem(
            "上下文超限自动降档",
            STATE_BUILTIN,
            "请求超出上下文时自动降到更低档位重试，不直接失败",
        ),
        SelfCheckItem(
            "请求侧裁历史",
            STATE_BUILTIN,
            "超长会话在请求侧裁剪，顶栏显示已裁掉的提示；本机历史不受影响",
        ),
        SelfCheckItem(
            "对话内三轮压缩",
            STATE_BUILTIN,
            "同一对话最多压缩 3 轮，顶栏胶囊显示「已压 x/3 轮」；用量到窗口 75% 即提前压；轮次只增不减（编辑/重生成不会让数字回退）；到上限提示新开对话继续",
        ),
        SelfCheckItem(
            "工具档「开发」档与分组",
            STATE_BUILTIN,
            "工具档分「能干活／省 token」两组，并新增「开发」档：只发开发链工具（终端／文件／GUI／日志／记忆），手机个人数据类工具不发送；降档顺序 正常 → 轻度 → 标准 → 极简",
        ),
        SelfCheckItem(
            "记忆自动精简",
            STATE_BUILTIN,
            "记忆超过 60 KB 时自动把最旧段落的正文移入冷区 MEMORY-ARCHIVE.md；环境包、硬边界、偏好、进行中项目永不搬动，可在记忆界面开关",
        ),
        SelfCheckItem(
            "返回栈去重（分享与回首页不再崩）",
            STATE_BUILTIN,
            "同一 key 不再重复入返回栈；分享后回首页、二级页互跳都不会再崩（Duplicate contentKey）",
        ),
        SelfCheckItem(
            "工具档",
            STATE_BUILTIN,
            "设置 → 工具档 六档可选（正常／轻度／标准／纯聊天／极简／开发），每档带说明文案；档位跟随所选模型",
        ),
        SelfCheckItem(
            "设置项搜索",
            STATE_BUILTIN,
            "设置页顶部可搜索设置项，直接跳到目标项",
        ),
        SelfCheckItem(
            "小窗动作条与两档缩放",
            STATE_BUILTIN,
            "小窗动作条含应用直通车；点按在最小/放大两档之间切，长按关闭并连应用一起关",
        ),
        SelfCheckItem(
            "虚拟屏与横竖屏稳定性",
            STATE_BUILTIN,
            "打开虚拟屏不再闪退；横竖屏切换的 native 越界与竞态已修",
        ),
        SelfCheckItem(
            "开机续跑提示",
            STATE_BUILTIN,
            "设备重启后如有被打断的自动任务，开机提示一次是否续跑（不自动重跑）",
        ),
        SelfCheckItem(
            "崩溃留证与导出",
            STATE_BUILTIN,
            "崩在启动／界面／后台都留证：调用栈 + 设备信息 + 本进程日志尾部写进 files/crash/，只留最近 10 份；设置 → 关于 可看条数并直接分享",
        ),
        SelfCheckItem(
            "定时自动备份",
            STATE_BUILTIN,
            "设置 → 数据备份 可选周期与保留份数（本机当前：每 6 小时／保留 10 份）；到点先比指纹，没变化不写文件；全量包内含数据库（eta.db），单包约 40 MB；每次打包前也会自动备一次；产物在共享目录，无常驻进程",
        ),
        SelfCheckItem(
            "数据占用与清理",
            STATE_BUILTIN,
            "数据占用页显示库大小／会话／消息／用量条数／运行归档数；三个清理项只动用量表与运行归档，不碰会话与分类",
        ),
        SelfCheckItem(
            "搜索跳转高亮",
            STATE_BUILTIN,
            "会话内搜索结果可跳转，并把命中的那一条高亮出来",
        ),
        SelfCheckItem(
            "消息选择复制",
            STATE_BUILTIN,
            "消息正文可整段复制，也能在段内拖选一段复制",
        ),
        SelfCheckItem(
            "会话批量删除与分类折叠",
            STATE_BUILTIN,
            "会话列表一键批量模式；分类分组标题可点折叠（跨重启记忆）；按分类删除会二次确认并保留置顶",
        ),
        SelfCheckItem(
            "胶囊常驻与最近会话",
            STATE_BUILTIN,
            "悬浮胶囊可设常驻；面板可放大、会话条目可点回；保留最近 3 条；跑完的「已完成」提示不自动消失",
        ),
        SelfCheckItem(
            "任务完成通知",
            STATE_BUILTIN,
            "跑完发通知（高优先级通道，归入流体云），点一下回到那次会话",
        ),
        SelfCheckItem(
            "重任务互斥闸门",
            STATE_BUILTIN,
            "重活（终端命令、构建等）同一时刻只跑一个，第二个明确排队，不互相拖垮",
        ),
        SelfCheckItem(
            "分享入口加固",
            STATE_BUILTIN,
            "分享纯文字／单图／多图到 Eta 都能进输入框；多图会去重并按来源补齐",
        ),
        SelfCheckItem(
            "朗读档位与熄屏续读",
            STATE_BUILTIN,
            "系统语音下可调语速／音调；熄屏继续朗读不被打断；正文逐段高亮跟随",
        ),
        SelfCheckItem(
            "中文单语与提示中文化",
            STATE_BUILTIN,
            "默认简体中文（语言列表只留 zh-Hans）；并发排队、失败重试等提示统一中文",
        ),
        SelfCheckItem(
            "工具档长按说明气泡",
            STATE_BUILTIN,
            "长按档位弹出说明气泡，悬浮展示，不占菜单空间",
        ),
        SelfCheckItem(
            "记忆界面与段落仓库",
            STATE_BUILTIN,
            "记忆界面显示热区／冷区与体积，可编辑并写回；段落化拆分与去重由仓库统一处理",
        ),
    )

    return SelfCheckReport(permissions = permissions, storage = storage, builtin = builtin)
}
