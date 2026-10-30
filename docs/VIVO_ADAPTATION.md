# vivo（OriginOS / 蓝心小V）适配现状

本文件记录本 fork 在 vivo 设备上的适配范围、每项的验证方法、以及已知缺口的原因。
结论都来自真机实测或反编译证据，复发时按「排障速查」逐条对照即可。

设备基线：OriginOS，蓝心小V `com.vivo.ai.copilot`（已从 6.10.0.1 升到 6.11.0.1 验证）。

## 一、已完成

### 1. 原子岛（超级岛）显示与点击

岛卡片由 SystemUI 插件 `com.vivo.systemuiplugin.superx.statusbar.SuperXCapsuleDataController`
解析**通知 extras** 产生，我们只写 `notification.superx.island` 这一个 Bundle。

关键字段（反编译自 `addAtomIslandElementToShow`）：

| 字段 | 说明 |
|---|---|
| `island.superx.leftTemplate` / `rightTemplate` | 必填且非 0，缺失会直接 return |
| `island.superx.leftInfo` / `rightInfo` | 必填 Bundle；图标/文案在 `…leftInfo.icon`、`…rightInfo.content` |
| `island.superx.islandClick` | 1 = 点击触发响应 |
| `island.superx.clickResp` | 点击的 PendingIntent（指向 Eta 主界面） |
| `island.superx.landingPkg` / `landingInfo` | 落地包与深链 |
| `island.superx.showTime` | 停留秒数；**-1 会被替换成系统默认（近似永久）**，必须给有界值 |

实现：`app/src/main/kotlin/io/github/mangi/eta/agent/island/VivoCapsuleExtras.kt`
（默认 `CAPSULE_SHOW_SECONDS = 8`，运行期间每次通知更新都会续期）。

注意：早期只写 `notification.superx.capsule` 是**精简分支**（面向不支持原子岛的设备），
点击字段不生效，且与 island 同时存在会让插件三路监听器对同一 key 重复投递
（日志表现为同一毫秒多次 `postIslandDataBean … operation: 0`）。已移除。

验证：

```sh
dumpsys notification --noredact | grep -A6 io.github.mangi.eta   # 应看到 notification.superx.island
logcat -s IslandServiceProxy                                     # postIslandDataBean operation: 0
```

### 2. 系统默认数字助理

vivo 的 `RoleManager` 不接受第三方申请 `android.app.role.ASSISTANT`
（`cmd role get-role-holders` 恒为空，`ACTION_VOICE_INPUT_SETTINGS` 页面也是空的），
所以走 root 直接改写两个 secure 键：

```
secure assistant                 = io.github.mangi.eta/io.github.mangi.eta.agent.voice.EtaVoiceInteractionService
secure voice_interaction_service = 同上
```

实现：`app/src/main/kotlin/io/github/mangi/eta/agent/device/AssistantSecureWriter.kt`，
由设置页「系统助手接管 → 数字助理应用」触发；写入后**立即生效，无需重启**。

验证：

```sh
settings get secure assistant
dumpsys voiceinteraction | grep -E 'mComponent|Session service'
# 期望：mComponent=io.github.mangi.eta/.agent.voice.EtaVoiceInteractionService
```

### 3. 个人数据（笔记 / 待办）

`agent/context/VivoPersonalContextQuery.kt`，走小V 的个人数据接口，工具名在
`AgentToolRequirements` 里标记 `vivoAlternative`。

## 二、已知缺口

| 缺口 | 现状 | 原因 |
|---|---|---|
| `hook/vivo`（接管小V 对话） | 代码齐（12 个文件），但**从未注入成功** | `/proc/<copilot>/maps` 里没有模块映射，LSPosed 作用域已含 `com.vivo.ai.copilot`、systemui/system_server 均可注入，唯独小V 进程被 Zygisk 层跳过（KernelSU `.umount` 为空、Zygisk Next `denylist_enforce=2`） |
| `AssistantManager`（无 root 场景的系统级接管） | 未生效 | system_server 侧没有任何 `EtaAssistant` 日志，说明 `onSystemServerStarting` 未跑到；已被 root 方案覆盖 |
| 长按电源键接管 | 无效 | OriginOS 电源键绑定关机菜单，与 assistant 无关 |

> 注意：由于默认数字助理已经是 Eta，**“接管小V 对话”在功能上不再必要**——
> 语音键/助手指令会直接路由到 `EtaVoiceInteractionService`。`hook/vivo` 仅在
> “系统助手仍是小V”的场景才有意义。

## 三、6.11.0.1 升级验证

升级小V 不需要改代码，按下面四项确认即可：

```sh
# 1. 版本与编码（我们的门槛是 versionCode > 0，6110001 通过）
dumpsys package com.vivo.ai.copilot | grep -E 'versionName=|versionCode='

# 2. 默认助手是否被系统升级重置（正常应仍指向 Eta）
settings get secure assistant
dumpsys voiceinteraction | grep mComponent

# 3. 岛是否仍工作（与版本无关，走 SystemUI 通知协议）
dumpsys notification --noredact | grep -c 'notification.superx.island'

# 4. 若日后要启用 hook/vivo，看注入与 hook 报告
grep -c mangi /proc/$(pidof com.vivo.ai.copilot)/maps    # 期望 > 0，实际长期为 0
```

实测：升级到 6.11.0.1 后，第 2、3 项均正常，第 4 项仍为 0（与升级前一致）。

## 四、排障速查

```sh
# 岛不显示
logcat -s IslandServiceProxy                    # 是否 postIslandDataBean
dumpsys notification --noredact | grep superx    # 通知是否带 island bundle
# 岛残留不消失
grep CAPSULE_SHOW_SECONDS app/.../VivoCapsuleExtras.kt   # -1 会近似永久

# 默认助手没生效
settings get secure assistant                    # 空 → 点设置页那行（走 root 写入）
logcat -s EtaAssistant                           # 期望 apply: … ok=true

# 模块是否注入某进程
grep -c mangi /proc/<pid>/maps
```

## 五、经验教训（避免重复踩）

1. **先确认数据通路，再改行为**：岛的点击问题绕了三轮，根因是写错了 Bundle（capsule vs island）。
2. **`showTime = -1` 不是“默认值”，是“几乎永久”**。
3. **每次换模块 APK，SystemUI 必须重启**才会加载新的 hook 代码（`killall com.android.systemui`）。
4. **vivo 不给第三方 ASSISTANT 角色**，别在 RoleManager 上耗时间。
5. 模块日志走 `android.util.Log`（tag `EtaIsland` / `EtaAssistant`）才抓得到，LSPosed 的
   journal 不落 logcat。
