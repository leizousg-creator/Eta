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
