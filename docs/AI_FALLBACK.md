# AI 实时补翻 MVP

AI 默认关闭。正式语料（含本地与辅助语言）永远优先，AI 缓存独立保存，不改变缺失语料的判断与收集，不新增 Mixin。

## 原始调用链与接入点

以下路径相对 `src/main/java/com/wynnchayuan/`。

| 环节 | 类与方法 | 本次处理 |
|---|---|---|
| 原始文本入口 | `listener/CaptureListener`、`ChatListener`、`TrackerListener`、`ScoreboardListener`；现有渲染 Mixin | 继续使用 Wynntils 事件、StyledText 和 Component |
| 标准化 | `capture/LineParts.of`、`GlyphSplitter.toTemplate` | 提取动态数字、符号、专名占位符，复用原来的模板与重建 |
| 正式查询 | `translate/TranslationStore` | 保持纯正式索引，AI 不影响 `hasTranslation`、前缀匹配和缺口收集 |
| 完整行翻译 | `translate/LineTranslator.translate`、`translateOfficial` | 全部原有正式查询失败之后，进入 AI fallback |
| 名牌、聊天 | `LineTranslator.translateLabel`、`translateChatWithAi` | 保留专用布局与过滤规则，最终 miss 才调用 AI |
| 多行试探 | `LineTranslator.translateBlock` | 保持纯正式查询，避免枚举组合时发送多余请求 |
| 对话逐字显示 | `render/DialogueOverlay`、`DialogueRewriter` | 保留前缀、字型、排版、打字与 Marquee 逻辑；未知文本稳定 800ms 才允许发请求 |
| 缺失收集 | 现有 `CaptureListener` 等 → `CaptureStore.record` | 仍独立于 AI；有 AI 译文也仍是正式语料缺口 |
| 正式缓存 | `translate/TranslationCache` | 不变；AI 使用单独文件 |
| 设置 | `client/SettingsScreen.aiRows` | F6「资料」加入启用、URL、Key、Model、语言、清缓存 |
| 生命周期 | `WynnChaYuan`、`TrackerListener` | 加入/断开、世界/角色切换、重新加载、配置修改使旧请求失效 |

原始数据流：事件／现有 Mixin → StyledText → LineParts 标准化 → TranslationStore 正式查询 → 原有样式重建；无结果则沿用原文／辅助语言，收集器继续记录缺口。

新数据流：正式查询失败 → `AiTranslations.fallback` 过滤 → 内存缓存 → in-flight 去重 → 有界队列 → 异步 Provider → 校验 → epoch 检查 → AI 缓存 → 原有渲染查询和样式重建。提交请求的那一帧返回空结果，让调用方继续原来的显示。

## 新增类

全部位于 `ai/`：

- `AiTranslationProvider`：异步接口。
- `TranslationContext`：目标语言、类型、说话者、任务名和相关术语。
- `OpenAiCompatibleProvider`：`POST {baseUrl}/chat/completions`，只读取标准 `choices[0].message.content`。
- `AiPromptBuilder`：稳定的 system 规则；source 和上下文放在独立 user JSON 内容中。
- `PlaceholderValidator`：检查占位符内容、顺序、重复数量、换行、空输出、外加 Markdown 和包裹引号。
- `AiTranslationConfig`：不可变配置，日志字符串隐藏凭据。
- `AiTranslationCache`：独立、有限容量、异步批量持久化缓存。
- `AiTranslationService`：缓存、队列、去重、并发、冷却和 session 管理。
- `AiTranslations`：最终显示 miss 的小型适配层、相关术语和对话稳定时间判断。

修改的主要类：`WynnChaYuan`、`SettingsScreen`、`LineTranslator`、`ChatListener`、`ChatBlock`、`TrackerListener`、`DialogueOverlay`、`DialogueRewriter`、`TrackerOverlay`、`LookAtTranslator`、`WynntilsText`。另修改七种界面语言资源、Gradle 检查任务、CorpusExport 的网络边界检查和 README。没有复制参考项目代码或加入新的 Mixin。

## 请求与缓存规则

- 并发最多 **2**，等待队列最多 **64**。队列满时当前帧保留原文，后续查询可重试。
- 去重包含已排队和正在发送的请求。缓存键是 SHA-256，输入包括目标语言、类型、标准化 source 和 promptVersion，并使用长度前缀避免拼接歧义。
- 最大输入 4000 字符，输出 16000 字符；内存缓存最多 10000 条，达到上限淘汰旧条目。
- 连接超时 10 秒，HTTP 请求 30 秒，Service 最长等待 35 秒。生产代码使用 `sendAsync`，渲染线程不等待网络或磁盘。
- 无效输出冷却 30 秒；网络／HTTP 错误触发整个 Provider 冷却；429 为 120 秒，401／403 为 300 秒。
- 切世界或修改设置会递增 epoch；旧结果不会写缓存，也不会操作 UI。已经发送的旧请求仍占用并发槽，直到结束或超时。
- 后台只更新缓存和 revision。显示端在客户端线程按原有路径重新查询；显示缓存随 revision 失效。
- 缓存每秒批量写入，使用原项目 SafeFiles 原子替换。关闭时安排最后一次保存；强制杀进程可能丢失最近尚未写盘的结果。
- 请求体不包含 API Key；Key 仅用于 Authorization 请求头。日志不打印 Key、URL、完整配置、响应正文或源文本。

相对 Minecraft 游戏目录：

```text
config/wynnchayuan/ai.json
config/wynnchayuan/ai-cache/cache.json
```

缓存每项记录 source、translation、language、type、provider、model、promptVersion。文件包含多种语言，键隔离语言和上下文类型。AI 不写正式 translation JSON 或 captured.json。

## 配置 API

F6 →「资料」→ AI 补翻区域：填写 Base URL、API Key、Model。目标语言留空跟随 WynnChaYuan，也可填写 `zh_cn`。点击字段旁的应用按钮／完成保存，再启用 AI。

Base URL 是 API 根路径，**不要填写 `/chat/completions`**；Provider 自动追加这段路径。需要 `/v1` 的服务应在 Base URL 中保留 `/v1`。模型填写服务商当前可用且支持 Chat Completions 的模型标识。

DeepSeek 示例（2026-09-19 查阅[官方文档](https://api-docs.deepseek.com/)）：

```json
{
  "enabled": true,
  "baseUrl": "https://api.deepseek.com",
  "apiKey": "替换为自己的密钥",
  "model": "deepseek-flash",
  "targetLanguage": "zh_cn"
}
```

其他 OpenAI-compatible 服务只需修改 URL、Key 和 Model；代码没有绑定厂商。必须返回非流式 Chat Completions 的标准 content 字符串。

API Key 在 F6 中显示为星号，旁白也不读出密钥；配置文件在本机以明文保存。启用后，符合原有翻译范围的缺失文本模板和相关上下文会发送到所配置的服务。现有玩家聊天过滤继续生效，但文本过滤不等同于绝对匿名化。

## 验证

使用 Java 25、Gradle 9.5.1 和 Wynntils Fabric 26.2 依赖；依赖 jar 位于被忽略的 `libs/`，也可由相邻的 `../wynntils` 项目提供。

```text
gradle runAiChecks runAiFallbackChecks
gradle build
```

新增两个 main-based 测试任务，已接入 `check`：

- `AiTranslationTest`：占位符、缓存重载、去重、最大并发、失败冷却、清缓存、配置切换、过期 session；本地 HTTP 测试服务验证请求结构、401／429／500、坏 JSON 与无效 URL。
- `AiFallbackTest`：使用真正的 LineTranslator 和 TranslationStore，验证正式命中不调用 Provider、100 次重复查询只有一个请求、动态数字模板化与还原、AI 不隐藏正式缺口、后来新增正式译文覆盖 AI、关闭后不发请求。

已完成全量构建和项目原有 headless 回归。没有使用用户密钥调用真实付费 API，也没有声称完成 Wynncraft 服务器内的实机视觉验收。

实机验证：安装 `build/libs/wynnchayuan-0.2.1.jar`（不要装 sources.jar），配置并启用 AI；找一段未收录的 NPC 对话或 tooltip，观察原文先出现、译文随后出现；重启验证缓存；错误 Key 验证原文仍能显示；请求期间退出服务器验证无旧会话补译。再加入该文本的本地正式译文并重新加载，确认正式译文优先。

## MVP 限制与后续方向

2026-09-19 补翻修复：提示词 v2 区分独有装备专名与通用物品标签，允许翻译 Unidentified Dagger、Junk Item 等。用已配置的 DeepSeek 实测，七个 `{#}` 后的 Unidentified Dagger 返回“未鉴定匕首”。旧提示词缓存自动失效，无需手动删除文件。实际完整语料下的碎石、垃圾物品就地替换已加入回归测试。

- 逐字对话采用 800ms 稳定判断。服务端停顿很长时可能翻译中间片段；没有新增对话完成协议，也没有按比例重写打字动画。
- 连续渲染的 tooltip、对话、追踪栏和注视面板能自动读到新缓存。只在事件到达时替换的中央标题、就地替换名牌，要等下一次原有事件才应用新译文；不会为迟到的结果重播已经消失的标题。计分板附加面板随原有计分板更新刷新。
- 聊天没有逐帧重建历史消息。等待中的合法服务器消息通过 ChatBlock 最多保留 45 秒，返回后本地补显译文；即使选择替换模式，首次在线 miss 也采用补显。不会回写服务端或翻译玩家发言。已有部分正式译文的多行聊天块不会为了补齐剩余行发整块 AI 请求。
- 相关术语只取运行时正式索引中匹配的最多 12 项；目标语言与正式索引不一致时不附带该索引的术语。未把完整 GLOSSARY.md 作为运行时解析输入；其专名保留规则已落实到提示词。
- speaker 暂为空，任务对话可附带当前 questName。类型采用 TEXT／LABEL／CHAT／DIALOGUE／CHOICE；不按每个捕获入口细分。
- 校验可拒绝格式破坏，不能保证翻译语义正确，也不能绝对保证模型遵守提示词。占位符顺序严格保留，可能拒绝需要调整语序的正确译文。
- 切换模型或 API 服务会让在途请求失效，但保留原有有效缓存；需要重新翻译时使用 F6「清除 AI 缓存」。

下一阶段优先增加：小型连接测试和失败状态显示、AI 缓存人工审核与导出、基于 GLOSSARY.md 的结构化 KEEP／术语表、明确的对话结束判定，以及事件驱动标题／名牌的安全刷新。先改善可诊断性和译文质量，再考虑更多 Provider 或计费统计。
