# 模块迁移清单（modular-3.3.1）

本分支以 **真上游 `Mangi-11/Eta`** 为基线，把 Eta-vivo 基线上我们需要的功能作为**模块**逐项迁入。
原则：**上游已有的能力以上游为准**，不再维护自己的实现；上游没有的才作为模块长期维护。

- 基线：`Mangi-11/Eta` v3.3.1（10-14 04:43）
- 参照线：`main`（lejw0925/Eta-vivo 基线，日常在用）
- 迁移来源：`main` 上的实现（等价能力），按模块拆成独立提交

## 模块状态

| 功能 | 上游是否已有 | 来源（main/参照线） | 新增文件/接线点 | 状态 |
|---|---|---|---|---|
| 实验性虚拟屏 | 否 | main | 见下「虚拟屏接线点」 | 🟡 文件与字符串已迁（`63d7b20`），接线待补 |
| vivo 支持（蓝心小V／文字语音／原子笔记） | 否 | main | 待盘点 | 🔴 未开始 |
| Hook 流体云（原子岛） | 否 | main | 待盘点 | 🔴 未开始 |
| 触发器（定时/事件，23 种） | 否（上游有 `tasks` 但无此触发器体系） | main | 待盘点 | 🔴 未开始 |
| 侧边栏排版优化 | 待核对 | main | 待盘点 | ⬜ 未核对 |
| 提示词包（PromptKit） | 否 | main | `data/promptkit/*`、`ui/screens/promptkit/*`、`ui/app/AgentPromptKitStore.kt`；接线：`SettingsScreen` 入口、`AppRoute.PromptKit`、`AgentAppRoot` entry、`AgentToolRequirements`/`AgentLocalTools` 的 `prompt_kit`、`AgentPromptBuilder` 注入、**`EtaApp.onCreate` 调 `PromptKitBridge.init(this)`**（缺这一步会导致页面能开但读写全空转，main 已修 `4ddc486`） | ⬜ 待迁 |
| 随包技能包（assets zip → files/skills） | 否 | main `59a8308` | `assets/skill-bundles/pojia-v4.zip`（1.93MB / 63 技能）、`data/promptkit/SkillBundleInstaller.kt`；接线：`PromptKitSkillSync.sync()` 首行调用 | ⬜ 待迁 |
| 技能列表懒加载 | 待核对上游 | main `ff9eb0e` | `ui/screens/skills/AgentSkillsScreen.kt` 三组 `itemsIndexed` + `EtaPreferenceGroupItem` | ⬜ 待核对（上游若已懒加载则丢弃） |
| 技能同步到 Linux 环境 | 否 | main | `data/promptkit/PromptKitSkillSync.kt`（经 Root 通道） | ⬜ 待迁（随提示词包） |
| 首 token 分阶段打点 | 否 | main | `agent/model/AgentLoop.kt` + `core/EtaTrace.kt` | ⬜ 待迁 |
| 分块文本读入窗口兜底 | 否（上游有分块存储，无我们的兜底策略） | main | `data/db/ChunkedTextDao.kt` | ⬜ 待迁（需先确认上游实现） |

> 状态图例：🟢 完成并验证 / 🟡 进行中 / 🔴 未开始 / ⬜ 待核对

## 虚拟屏接线点（`63d7b20` 遗留）

1. `AndroidManifest.xml`：display/服务声明
2. `AppRoute` + 导航 entry：设置页入口与页面注册
3. 设置页字符串与分组项（`SettingsScreen`）
4. Root 授权与 display 创建/销毁生命周期
5. 虚拟屏内的输入、截图、文本输入通道

## 迁入约定

1. **一个模块 = 一组提交**，只新增文件 + 少量明确接线点，不改上游已有文件的内部逻辑。
2. 接线点收敛在少数"汇聚文件"（路由表、工具目录、能力合同、设置页入口），上游改动这些文件时冲突范围可控。
3. **不改上游数据库版本**：模块不得引入 schema 变更；确需变更时单独编号并记录，避免"版本错位清库"。
4. 每个模块必须能单独回退（提交可 revert）。
5. 迁入前先确认上游当前 HEAD 是否已有等价能力；有则直接删掉我们的实现。

## 上游跟进流程

1. 定期检查两条上游：`Mangi-11/Eta`（基线）与 `lejw0925/Eta-vivo`（参照线）。
2. 上游发版/有新提交时：`git fetch upstream && git merge upstream/main`（依赖真实历史，见下节），而不是逐文件对照。
3. 合并后按本清单逐项复验模块；上游已具备的能力，删除对应模块。
4. 合并干净后打标记 `sync-upstream-<tag>`。

### 历史结构要求（重要）

`modular-3.3.1` 当前的基线是**整包快照**（`ab13c2b`），与上游没有共同历史，`git merge upstream` 无法使用。
因此需要对基座做一次重建：以 `Mangi-11/Eta` 的真实历史为根，再把我们的模块提交以补丁形式应用上去。
重建之后，后续上游更新才能用 `merge` 平滑跟进。
