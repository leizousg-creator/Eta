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

## 五、原子岛降级（2026-11-14）

排查结论：LSPosed 已把 Eta 模块注入 `com.android.systemui`（`/proc/<pid>/maps` 可见 mangi），
但 `com.vivo.ai.copilot` 始终拿不到注入 —— 模块启用、作用域、denylist、zygote fork 全部正常，
仍不注入。于是 `WidgetTaskService.onBind` 的 hook 不存在，bind 回来的是原生 `BinderProxy`，
`VivoIslandWire` 判定 `module bridge unavailable`。

对应实现：`fork/island/MediaSessionIslandFallback.kt` —— 用 MediaSession + MediaStyle 通知
承载 Agent 阶段与工具名，靠 vivo 对媒体会话的系统级支持争取上岛。由
`AgentRuntimeIslandBridge.unavailable()` 触发，update/finish/close 同步刷新，不影响任务执行。
