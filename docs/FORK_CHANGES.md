# Eta fork 定制清单

这份清单解决一个具体问题：**每次同步上游，怎么快速知道哪些地方是我们的改动**。

同步上游时的流程：

```sh
grep -rn "ETA-FORK" app/src/main/kotlin      # 所有侵入上游文件的钩子
git log --oneline upstream-base..HEAD        # 我们的提交
```

## 一、纯新增功能（上游没有，永远不会冲突）

| 模块 | 位置 | 说明 |
|---|---|---|
| 计划模式 | `agent/model/AgentPlanMode.kt` | 只读探索 + 工具层硬拦截；开关在设置页 |
| 网络代理与重试 | `data/model/NetworkProxySettings.kt`、`agent/net/EtaProxySelector.kt`、`data/repository/NetworkProxyRepository.kt`、`ui/screens/settings/NetworkProxyScreen.kt` | 代理地址 / 单节点链接 + 重试次数与退避 |
| 重试策略 | `agent/model/AgentRetryPolicy.kt`、`data/model/AgentRetrySettings.kt`、`data/repository/AgentRetryRepository.kt` | 错误分类 + 指数退避 |
| PromptKit 提示词包 | `data/promptkit/*`、`ui/app/AgentPromptKitStore.kt`、`ui/model/PromptKitUiState.kt`、`ui/screens/promptkit/*` | 多版本附加提示词、技能包同步、触发关键词 |
| 内置角色卡 | `app/src/main/assets/builtin_characters/*.json` | 6 张卡随包发布 |
| 直投桥接 | `fork/virtualdisplay/HostSurfaceBridge.kt` | host surface 直投，Controller 只留转发 |
| 技能索引缓存 | `data/repository/AgentSkillsStore.kt`（复用缓存部分） | 进技能页不重扫 116 个目录 |

## 二、侵入上游文件的钩子（同步时必须逐个核对）

| 文件 | 位置 | 我们的改动 |
|---|---|---|
| `agent/tool/AgentLocalTools.kt` | 工具执行入口 | 计划模式：写类工具返回 `PLAN_MODE_READ_ONLY` |
| `agent/model/AgentPromptBuilder.kt` | 系统提示拼装 | 注入 `AgentPlanMode.prompt` 约束段 |
| `agent/model/AgentLoop.kt` | 单轮结束处 | 插话中断：`completedRound == null` 时发 `ModelRequestInterrupted` 并重规划 |
| `agent/model/AgentToolCatalog.kt` | 工具目录 | `ModuleFlags.toolsEnabled` 决定是否挂 PromptKit 工具 |
| `data/repository/CharacterRepository.kt` | 播种 | 读 `assets/builtin_characters/` 全部内置卡；`restoreBuiltinCharacters()` |
| `ui/SettingsScreen.kt` | COUI 与 Miuix 两套分支 | 六个入口：提示词包、出站改写、导出诊断包、虚拟屏空闲回收/时长/刷新率；另有计划模式、网络与重试 |
| `agent/virtualdisplay/VirtualDisplayController.kt` | 截图前 + 公开 API | 直投转发（`attachHostSurface`/`detachHostSurface`）+ 截图前切回 ImageReader |
| `agent/accessibility/AgentAccessibilityService.kt` | `collectNodes` 返回类型 | 改成 `String?` 并加 `queryExecutor.ensureActive()` |
| `agent/runtime/AgentRuntimeService.kt` | 虚拟屏快照回调 | 快照由 active 变 inactive 且无 run 时撤掉胶囊与边缘光 |
| `data/datastore/SettingsDataStore.kt` | import 区 | 网络代理 / 重试 / 虚拟屏设置在同一个 Store |

## 三、虚拟屏架构取舍（重要）

上游 v3.4.2 把 `VirtualDisplayController` 重构成 session 化（`VirtualDisplaySessionResources` +
`VirtualDisplaySessionState` + `creationLock`），并新增 `onTopPackageObserved`、`checkCancellation`。

我们的做法：**以上游 session 架构为底**，直投能力抽到 `fork/virtualdisplay/HostSurfaceBridge.kt`，
Controller 里只保留：

```kotlin
// ETA-FORK: 直投（fork 专有，见 fork/virtualdisplay/HostSurfaceBridge.kt）
internal fun sessionResourcesForFork(): VirtualDisplaySessionResources? = ...
fun attachHostSurface(surface: Surface): Boolean = ...
fun detachHostSurface(): Boolean = ...
val isHostSurfaceAttached: Boolean get() = ...
```

截图路径额外加一行 `HostSurfaceBridge.ensureReaderSurface(...)`，因为直投时 ImageReader 收不到帧。

上游以后再改 session 层，只需要重新对这四个转发和那一行做适配。

## 四、同步上游的固定流程

1. `compare vX..vY` 拿变更清单，统计与我们改过文件的重叠
2. 以上游新版本建基线（`fetch_theirs.py` 逐文件走 Contents API，避免几十 MB 的 tarball）
3. 三方合并（`merge3.py`，base=旧上游 / ours=我们 / theirs=新上游）
4. 无冲突的直接采用；冲突的按「保留我们语义、吸收上游结构」处理
5. `grep -rn "ETA-FORK"` 逐点核对
6. 本机 `:app:compileDebugKotlin` 编译，再走 CI 出包
7. **入口清单比对**：上游重写整页 UI 时，光编译通过不够，要对照旧版把功能入口一个个点一遍

## 六、命令守门人 / 超时语义 / 自动备份 / BACK 导航

| 能力 | 位置 | 说明 |
|---|---|---|
| root 命令守门人 | `fork/command/DeviceCommandPolicy.kt`、`RootShellTerminalController.execInTerminal` | 三档（relaxed 默认 / strict / full），只作用于 `environment=android` + `identity=root`；拦不可逆操作（含 `mkfs.*`）与 `/dcim` `/pictures` `/android/data` `/android/obb` 写入；路径先归一化 `/sdcard`、`/storage/emulated/N` 等别名再比；被拦返回 `POLICY_BLOCKED`，工具审批覆盖不了 |
| 档位设置 | `SettingsScreen` 工具组、`EtaApp.restore` | 点击循环切换，存 `eta_root_command_policy`；非法值回落 relaxed |
| 超时语义 | `RootShellTerminalController` 两处结果构建 | 超时补 `execution=unknown` + `next_step`（先观察状态，别直接重放），与非超时失败区分 |
| 自动备份 | `fork/backup/AutoBackup.kt`、`EtaApp` | 每 6 小时写 `Download/EtaAutoBackup/eta-auto-<时间戳>.zip`，保留 3 份，顺带清理 `IS_PENDING` 残片；Robolectric 环境跳过（否则污染 cacheDir 让备份测试失败） |
| BACK 导航 | `AgentAppRoot` | BackHandler：先收侧边栏，再退一层路由，退无可退才交系统——之前一按就退出到桌面/全局搜索 |

**原子岛（vivo）结论：第三方拿不到卡片形态（2026-11-19 已推翻，见第七节）。** 两条路都试过并回滚：
1. 原生桥接要 hook `com.vivo.ai.copilot` 的 `WidgetTaskService.onBind`，但该进程始终拿不到 LSPosed 注入（systemui 能注入）
2. 通知 extras 需要 `callbackInfos.serviceCodes` 里的注册凭证（夸克是 `quark_openToken`），自己编的会被插件整条跳过——`dumpsys` 里我们的 `notification.superx.island` 永远是 `mParcelledData`，说明插件根本没读

现在保留 `VivoCapsuleExtras` 原实现（islandType=2 / leftTemplate=3 / rightTemplate=7），效果是胶囊 + 阶段文案。**别再去动 template / forceShowCard，两次尝试都是倒退。**

## 七、收编 EtaRedirect 模块（2026-11-19）

来源：`Download/eta蓝心小V 重定向/EtaRedirect-v1.0.apk`（包名 `com.eta.copilotredirect`，老 XposedBridge 实现，1051 行 Hook.java）。只抄了两处，其余不搬。

| 改动 | 位置 | 说明 |
|---|---|---|
| SuperX 白名单放行 | `hook/system/SuperXValidatorHooks.kt` | hook `com.android.server.notification.VivoSuperXNotificationManager.isValidSuperXNotification(NotificationRecord, boolean)`，仅对 `io.github.mangi.eta` 返回 0（ROM 自身通过值），其余应用走原逻辑。挂在 `SystemServerHooks` |
| 唤醒重定向 | `hook/vivo/VivoWakeupRedirectHooks.kt` | hook `com.vivo.ai.copilot.framework.wakeup.WakeupService.onStartCommand/onBind`，拦白条前缀 `com.vivo.action.copilot.SHOW_WHITE_STRIP_BY_HOMEKEY` 与 `START_CIRCLE2SEARCH` / `WAKEUP_AGENT_BY_POWER` / 语音热词等 10 个 action，改拉 `EtaVoiceAssistActivity`；1.2s 去重；启动失败回退原逻辑 |
| 唤醒入口回退 | `agent/voice/EtaVoiceInteractionService.kt` | `requestSession()` 失败（未设为默认助理 / `:voice` 被杀）时直拉 `ui.MainActivity`，避免"按了没反应" |

**上一节"第三方拿不到卡片形态"的判断作废。** 真正卡点不是 SystemUI 插件层的 `callbackInfos.serviceCodes` 凭证，而是 system_server 里 `VivoSuperXNotificationManager` 的发送方白名单校验——一个 hook 就能过。`dumpsys` 里 `mParcelledData` 是 Bundle 未 unparcel 的正常形态，不能作为"插件没读"的证据。

不搬的部分及原因：

- `AgentExecutionService.b()` / `d(boolean)` 短名 hook：release 开 `isMinifyEnabled=true`，短名每次构建漂移，我们是源码方不该反向猜；
- `AudioRecord.read` 全量 hook、`isOnBackInvokedCallbackEnabled`、`RuntimeService`/`OverlayService.onCreate`、心跳重发 notify：Eta 内部可直接改，隔进程 hook 自己是倒退。

### 原子岛（SuperX）落地条件（2026-11-17 本机实测）

链路要通必须同时满足四条，缺一条都是"看得见通知、看不见卡片"：

1. **system_server 白名单**：hook `VivoSuperXNotificationManager.isValidSuperXNotification` 放行 Eta 包名（`SuperXValidatorHooks`）。没有它通知根本到不了插件。
2. **顶层四个键**：`notification.superx.template=4` / `displays=0x111` / `operation=0` / `islandNotify=false`。只写 `notification.superx.island` 子 Bundle 时，插件收得到通知但不进 SuperX 分支。
3. **island 内部组合**：`template=4` / `leftTemplate=1` / `rightTemplate=4` / `forceShow=true` / `forceShowCard=false` + `leftInfo` / `rightInfo`。早期用的 `template=-1` / `3` / `7` 这组在本 ROM 上不出卡。
4. **续期**：`showTime` 只有几秒，系统仅在通知数据变化时刷新岛。`AgentExecutionService` 每 6 秒重发同一条通知（心跳），否则任务中途岛会消失。

验证用的日志锚点：

```
_V_superXdataUtils: isValidSuperXData result : 2241, template : 4, key : 0|io.github.mangi.eta|1107|VIVO_SUPERX_TAG|10300
_V_SuperXServicePluginImpl: onSuperXNotificationArrived : ...pkg=io.github.mangi.eta...
_V_SuperXUtils: get small icon , basicIcon = Icon(typ=RESOURCE pkg=io.github.mangi.eta ...)
_V_IslandGestureManager: islandBean : IslandId:0|io.github.mangi.eta|1107|...|10300
```

`isValidSuperXData` 返回 `2241`（位掩码）且 `template : 4` 就说明校验已放行。

### system_server 侧 hook 的崩溃面（2026-11-18 踩坑记录）

现象：新版本装上后 white strip / 圈搜 / 侧键全都不再拦截，**连原子岛的 `SuperX` 白名单也一起失效**
（通知 tag 从 `VIVO_SUPERX_TAG` 退回 `null`，`isValidSuperXData` 不再出现）。

根因链：

1. `VivoWakeupSystemHooks` 里用 `ActiveServices.declaredMethods` 枚举方法；
2. 该调用会解析类的**全部**方法签名，system_server 里碰到加载不了的 `@hide` 参数类型即抛 `NoClassDefFoundError`；
3. `NoClassDefFoundError` 是 **`Error` 不是 `Exception`**，`HookRegistrar.install` 的功能组隔离层只 `catch (Exception)`，兜不住；
4. 异常冒泡出 `onSystemServerStarting` → 整个 `SystemServerHooks` 安装中断 → 框架把模块判为失败 → 同进程内**已注册**的 hook 一并失效。

两条硬性纪律：

| 纪律 | 具体做法 |
|---|---|
| 不要枚举 ROM 类成员 | 用 `HookSupport.findMethod(clazz, name, vararg types)` 按确定签名逐个精确查找（它内部已 `catch LinkageError`）。确实需要按名字扫时，`getDeclaredMethods()` 必须包 `runCatching`，且失败要能降级 |
| system_server 的每个 hook 组自己兜底 | `SystemServerHooks.install` 用 `runCatching { block() }` 逐个隔离，任何 `Throwable` 只落一条 `warn`，不许拖垮同组其它 hook |

诊断锚点：`dumpsys notification --noredact | grep VIVO_SUPERX_TAG`。tag 是 `null` 说明白名单 hook 整体没跑，优先查模块安装是否被打断，而不是查原子岛字段。

### 语音热词不要做唤醒重定向

`com.vivo.agent.action.wakeup_by_voice` / `wakeup_start_only` 由 `com.vivo.voicewakeup` 的硬件 hotword 引擎发出
（日志 `sound_trigger_hw_default: wakeup event by yuemeng`），环境音误判也会触发。一旦拦截并拉起 Eta，
用户在任何应用里都会被突然弹出的全屏窗口打断。这两个 action 已从重定向列表移除，语音入口交回系统原生；
需要语音直达时应在设置侧做开关，并配 VAD（EtaRedirect 是在 `AudioRecord.read` 上做 RMS 判断），不要无条件转发。

### 双指长按跳 Eta：真凶是 `VivoWakeupRedirectHooks`（2026-11-25）

**现象**：在微信/图片页双指长按 → 跳 Eta；桌面上双指长按无反应。

**证据（logcat）**：

```
START u0 {flg=0x14200000 cmp=io.github.mangi.eta/.ui.MainActivity}
  from com.vivo.ai.copilot, uid 10260, pid 12256 (BAL_ALLOW_SAW_PERMISSION)
```

`0x14200000 = NEW_TASK | RESET_TASK_IF_NEEDED | CLEAR_TOP`，与 `hook/vivo/VivoWakeupRedirectHooks.launch()` 的 flag 组合逐字节一致；`from` 是小V 而不是桌面或 system_server。

**因果链**：vivo 的「双指长按圈选识别」走 `WakeupService` 的 `START_CIRCLE2SEARCH` → 被 `VivoWakeupRedirectHooks`（挂在 copilot 进程）拦下 → 拉起 Eta。桌面没有这个 action，所以只在应用内复现。

**为什么长期没发现**：该 hook 早期一直没执行过——copilot 进程注入失败（`/proc/<pid>/maps` 里模块条目为 0），我们据此认定"这条路不可用"。后来注入成功（`maps=2`），它才开始工作并暴露问题。**结论：`maps=0` 不能当成永久结论，注入状态会变。**

**排查中走过的错误方向（供以后避坑）**：

| 错误判断 | 实际 |
|---|---|
| 认为是 `ASSIST`/`ASSISTANT` filter 被识屏选中 | 删掉 filter 后现象不变；真凶在 copilot 进程 |
| 认为是 `AssistantManager` 重建 VIS stub | 停用后现象不变 |
| 认为是桌面图标误触 | 用户明确在空白处按的；那条 `START` 是另有其因 |
| 认为是 `mCircleSearchEnabled` 缓存旧值 | 两个设置（`system_navigation_wake_up_switch`/`copilot_float_service_setting`）本来就正常 |
| 认为是 `mixin`、launcher 注入问题 | launcher 侧代码只读设置，不启动 Eta |

**方法论**：判断"谁启动了某界面"时，唯一可靠的是 `START u0` 里 `from <pkg>` 和 `realCallingUid`，不要从配置反推。

### 长按电源键接管：不安全，永久停用

`VivoInputPolicy.startJoviVoiceByPowerKey` 不只是唤起小V，它还**消费这次电源键长按**（内部置 `mPowerKeyHandledByJovi=true`）。hook 里返回 `null` 跳过整个方法后，事件未被消费，系统继续执行电源键长按的默认行为——实测**直接重启手机**。

规则：hook 系统按键分支前，必须先分清目标是"入口"还是"入口 + 事件消费"，后者不能整段跳过。

### 应急开关（kill switch）路径修正

早期放在 `/data/data/io.github.mangi.eta/files/hook_off`（`drwx------`，仅 Eta 自身 uid 可进），而注入进程是 uid 1000/10072，`File.exists()` 恒 false，**该开关从未生效**，导致一轮排查误判。

现在检查三处：`/data/system/eta_hook_off`、`/data/local/tmp/eta_hook_off`、`/sdcard/Download/eta_hook_off`，任一存在即停用全部 hook；日志会打印 `检测到 <路径>，跳过全部 hook`。

## 八、从 `eta增强 2.15`（`com.lcsauce.etaplugin`）收编的三个机制

那个模块唯一目标就是注入 `io.github.mangi.eta`，改的是我们自己的提示词与门禁，功能内容不搬，只搬三个中立机制。

| 机制 | 位置 | 说明 |
|---|---|---|
| 提示词热加载 | `data/promptkit/PromptKitStore.appendText()` | 读 `files/promptkit/append.md`，回退 `/sdcard/Download/eta_append.md`；缓存键 `lastModified + length`，改文件即时生效；`PromptKitManifest.appendEnabled` 默认开；`promptFor` 把版本正文与追加段用空行拼接 |
| Hook 自检 | `core/HookSelfCheck.kt`、设置页两套样式各一个入口 | 注入进程写不了 Eta 私有目录、`RemotePreferences` 只有读方向，所以汇总走 logcat：`recordInstallation` 日志前缀补进程名，`HookSelfCheck` 用 root 读 `logcat -d -s Eta` 再按标记词过滤；点一下复制到剪贴板 |
| 文件应急开关 | `core/HookKillSwitch.kt`、`ModuleMain.onModuleLoaded` | `files/hook_off` 存在 → 任何进程都不装 hook 并 `detach()`；`files/reasoning_off` 关闭推理。路径写死包名，因为注入进程没有 Eta 的 Context |

不搬的：

- `DEFAULT_PAX` / `OVERRIDE` / `APPEND_TEMPLATE` 整套越狱提示词——其中 `UNTRUSTED_*_STRIP`、`MEMORY_NOT_INSTRUCTION_STRIP`、`ROOT_HESITATION_STRIP` 三条删的是真实防护（外部数据不得当指令、未授权时降级），删掉等于给 prompt injection 开门；
- 人格伪装（"Little Code Sauce" / "YG"）；
- `Ota.java`：`https://222.186.56.69:8761/eta/version.json`，裸 IP + 全信任自签证书 + 自动下载装 APK；
- 版本短名映射表（`kr`/`fr`/`w11`/`jm4` 按 3.3.5~3.4.4 各一套）：R8 每次构建都会重排，我们的包一升级它就失效。

## 九、vivo 入口归属与岛展示（2026-11 定版）

### 入口归属：四条路彼此独立

| 入口 | 系统侧路径 | 我们的做法 |
|---|---|---|
| 双指长按识屏 | `com.vivo.accessibilityenhance` → `CircleSearchExecutor` → 需要"一个助理" | **不接管**：删掉 Eta 的 `ASSIST` / `ASSISTANT` 声明 + `voice_interaction_service` 指向 Jovi |
| 长按电源键 | `VivoInputPolicy.startJoviVoiceByPowerKey`（硬编码点名 Jovi） | hook 该方法，命中拉起 Eta |
| 白条上滑 / 圈搜 | `startService` → `com.vivo.ai.copilot/.framework.wakeup.WakeupService` | hook `ActiveServices.startServiceLocked` 拦截 |
| 语音热词 | `com.vivo.voicewakeup` 的硬件 hotword | **有意放行**：误触发会全屏弹窗，未做 VAD 前不接管 |

三个容易误判的点：

1. **改 VIS 不影响电源键**：电源键走 `startJoviVoiceByPowerKey`，方法名里就写着 Jovi，跟"谁是助理"无关，也不经过 WakeupService。
2. **`ASSIST` 与 `ASSISTANT` 是替代关系**：删一个另一个立刻接上。删 `ASSIST` 后现象从"跳 Eta 聆听"变成"直接跳 MainActivity"（`requestSession()` 失败 fallback），两个必须一起删。
3. **删这两个 filter 的代价**：Eta 不再是 `RoleManager` 的数字助理候选，系统设置里无法再设为默认助理。

### 岛展示：只保留 extras 一条路

最终形态 = 通知 extras（`VivoCapsuleExtras`）+ `SuperX` 白名单 hook + 心跳续期，**不再用岛进程内投递**。

- **可点击的最小集合**：`island.superx.islandClick=1` 与 `clickResp`（PendingIntent），island 内和顶层各写一份。缺 `islandClick` 时 SystemUI 的 `VivoIslandController` 里 `mClickedIsland` 恒为 false，触摸被 QuickStep 当普通手势吃掉，表现就是"点岛没反应"。
- **岛进程内投递不能当主路径**：`com.vivo.systemuiplugin:island` 是按需启动的（有岛数据才 fork）。如果只靠广播投递，没有 extras 就没有数据 → 进程不启动 → 广播没有接收者，形成死锁（实测表现：完全没有任何岛）。
- **不要两条路并行**：extras 的岛 id 是通知 key，投递的 id 是自定义字符串，系统当两个独立条目 → 用户看到"两个岛"。
- **不要开 promoted ongoing**：`setStyle(Notification.ProgressStyle())` + `extras[android.requestPromotedOngoing]=true` 会把同一条通知再渲染成 Android 16 实时活动卡片，与岛胶囊并存 → 看起来像"又多个岛"。已移除（`AgentExecutionService` 内保留注释代码）。
- **心跳只在真有 run 时续期**：否则服务因长驻任务停在前台时会一直给岛续 `showTime`，任务结束后岛永久挂"操作中"。

### 前台服务不退的孤儿租约

日志锚点：`Execution service kept alive with idle run: leases=run:<id>`。

`run:<id>` 只在 `AgentRuntimeService.beforeTerminalPublish` 释放；run 被取消、抛异常或协议提前关闭而没走到终态发布时，这条 lease 再没人释放，前台服务与通知会一直挂着。

处理：`AgentExecutionService.refreshNotification` 在**确认没有活跃 run**（`runStatus == null && activeRunCount == 0`）时调用 `ExecutionLeaseRegistry.releaseRunLeases()` 回收 `run:*` 孤儿，并记 `Released orphan run leases: ...`。只在确认无 run 时执行，不会误伤活跃任务。
