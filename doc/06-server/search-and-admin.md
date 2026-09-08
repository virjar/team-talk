# 搜索与管理

## 1. 消息全文搜索

服务端使用 Lucene FSDirectory 和中文分词建立消息索引。典型索引字段：

- chatId、senderUid、clientMsgId、serverSeq。
- plainText（由 RichText Markdown 权威源派生）。
- timestamp 与可过滤的消息类型。

Lucene 接收 MessageBody 业务上限内的完整可搜索正文；Conversation `last_message` 是独立的最多
400 字展示投影。两者不能复用同一个截断结果，否则第 401 字后的正文会永久失去检索能力。

搜索前必须校验调用者是目标 Chat 成员；全局搜索也只能覆盖用户有权访问的会话。客户端不能通过
遍历本地历史模拟越权服务端搜索。全局授权集合在 Lucene 中使用单个精确 term-set filter 表达，不能
为每个 chat 构造一个 Boolean 子句；账号加入超过 Lucene 默认子句上限的会话后仍必须能够搜索。Lucene
命中属于派生结果，按 stored chatId 回读 MessageStore 前必须再次确认该 chatId 仍在本次授权集合内。

产品搜索接收纯文本而不是 Lucene 查询语言：关键词最多 1,000 个字符且不能包含控制字符，保留符号在
进入 `QueryParser` 前统一转义；仅精确的 `*` 保留为显式浏览全部消息的契约。底层适配器在创建 parser、
term-set 或 collector 前再次校验 canonical chat/sender、正向时间范围、最多 10,000 个精确 chat filter，
以及 `offset + limit <= 10,000` 的命中收集窗口。管理端和普通消息搜索共享同一窗口常量，不能让上层分页
校验与 Lucene 最终边界漂移。

## 2. 写入与更新

- 新消息以 clientMsgId 或稳定消息身份建立索引。
- 编辑用完整新正文覆盖同一索引文档，包括预览范围之外的内容。
- 撤回删除正文或写入不可检索状态，旧版本关键词不得继续命中。
- 转发作为目标 Chat 的新消息建立新文档。

聊天附件使用同一条消息投影中的独立名称字段和元数据摘要；完整消息仍只对应一份 Lucene 文档。
附件字段按 MIME 分类分开，避免一份消息中某个附件的名称与另一个附件的类型组合成假命中。
普通消息全文行为保持独立，图片、语音和视频没有正文也可以进入附件搜索。

索引是派生数据。写失败记录 fault 并进入重建/修复流程，不能回滚已经持久化的权威消息为“从未
发送”。

## 3. 恢复

生产启动在打开活动 `IndexWriter` 之前先对 MessageStore 与 Lucene 做完整一致性审计。MessageStore
按不可变消息 key 提供稳定 cursor；每页最多 256 条并受 32 MiB 原始 key/value/revision 字节预算约束，
页边界在 Message 解码前决定。若 head 单条超过预算仍只返回该条，cursor 从它之后继续，因此既不会
因大正文制造无界页，也不会在页首活锁；累计条数和字节数使用溢出检查。

审计逐条用稳定 projectionKey 精确查找 live document，比较最新 revision、clientMsgId、chatId、sender、
seq、messageType、timestamp、可搜索状态和完整派生正文，同时复验 Lucene 的 exact/point filter 与
timestamp doc-value；每个 live document 的单值 exact term 字段还必须恰好参加一个 term posting，不能
用额外的非 stored chatId/searchable term 绕过 stored 字段比较。最后用 live document 总数发现多余条目。
活动目录不存在、commit/schema marker 缺失、任一消息缺失、重复、revision/字段不符或多出条目，都会
触发从 MessageStore **当前消息值 + 当前 revision** 的全量重建。已撤回或空正文仍生成带最新 revision
的 tombstone，旧关键词不会复活。

重建只写固定同级 side 目录，整个扫描结束后一次 commit，再用第二轮权威审计验证 side；只有验证成功
才写 completion marker。发布使用同一父目录内的原子 rename：旧 active 先移到固定 backup，再把完整
side 移为 active，最后删除 backup。进程在任一步退出后，下次启动会丢弃无 marker 的 side、优先发布
完整 side（包括 marker 已落盘但 active 尚未开始搬移的窗口）或恢复 backup；active/side/backup 根路径
拒绝符号链接，清理遍历不跟随内部链接。平台不支持原子 rename 或残留目录无法安全收敛时启动失败，
不能用非原子复制发布半份索引。

审计/重建完成前 TCP、HTTP 和搜索都尚未开放，因此没有“一半新一半旧”的降级窗口。清洁索引只记录
`VERIFIED + authoritative count/encoded bytes` 并原地打开，不无条件重建；修复则记录 `REBUILT` 和同样
的有界统计，不把消息正文写入日志。

Lucene 的 analyzer、directory、writer 和 searcher manager 先在局部启动阶段全部获取，再组成一个
完整的 `OpenIndex` 实例一次发布。运行状态只由该资源组是否存在决定；搜索与投影在原有同步锁内
使用同一个完整实例，不维护相互依赖的可空资源字段。停止时先摘除资源组，再执行 commit 和逆序
关闭；启动中途失败则只逆序排空已获取的局部资源，清理阶段的取消或 fatal error 不得被原始普通
异常遮蔽。即使 commit 或某个 close 失败也继续排空其余资源；失败终态由同一实例永久
重放，后续 `stop` 或重新 `start` 不能把不确定的 native 终态伪装成成功。只有原始启动失败且回滚全部
成功时，同一实例才保留重试能力；启动回滚自身一旦失败，`start`/`stop` 同样精确重放合并后的终态对象。

## 4. 用户搜索

终端用户搜索查询 PostgreSQL 中的用户名、显示名和短号，不以手机号作为搜索键。公开目录只返回
状态正常的 `HUMAN` 身份；停用用户以及 `BOT`、`SYSTEM` 服务身份不会进入联系人、建群或文档授权候选。
数据库必须先应用身份可见性条件，再按 `(name, username, uid)` 确定排序并截取最多 20 条，不能在
截断后由客户端或领域服务过滤，否则隐藏身份会占用有限的结果名额。管理后台需要查看全部身份时使用
独立的管理目录端口。返回资料字段遵循 `User` 契约的隐私边界；是否为好友由客户端联系人数据或服务端
关系共同解释。

## 5. 管理后台

`admin/` 是 React/Vite 前端，调用服务端管理 API。管理能力至少包括：

- 单实例管理员认证、密码轮换、单会话/全部会话吊销与操作审计。
- 用户查询和状态查看。
- 封禁/解封，以及对活动连接与 token 的联动。
- Document 资产责任盘点与已封禁 steward 的受审计批量交接。
- 单组织树、成员归属和受管部门群。
- 通知机器人的创建、凭据轮换、停用与群授权。
- 基础运行指标和版本信息。
- 客户端遥测全文检索、设备运行信息，以及按 uid/deviceId/phone 启停限时诊断采集。

管理后台采用 Vite 7 的浏览器构建目标：Chrome/Edge 107、Firefox 104、Safari 16 及以上；更旧浏览器
不在支持范围内。这是构建目标，不代表已在每种浏览器完成验收；依据见
[Vite 7 迁移说明](https://v7.vite.dev/guide/migration#default-browser-target-change)。
构建环境与依赖升级方法见[依赖维护](../08-development/dependency-maintenance.md)。

组织路由 `/api/admin/organization/**` 是管理写控制面；普通 Android/Desktop 目录读取不复用这些
JSON 响应，而是走 `OrganizationRpc` 的二进制 revision-fenced 分页。控制面提交成功后可以广播一个
不含目录行的瞬时 revision 提示，不能把该提示或 HTTP 返回当成终端本地投影的权威快照。
删除当前负责人的直属归属会返回 409 且不修改任何事实；管理员必须先编辑组织节点变更或清空负责人，
再重试成员删除，不允许留下指向非成员的 `leaderUid`。

管理认证与普通用户 token 隔离。单实例管理员用户名和密码 verifier 由 PostgreSQL 保存；环境变量只用于
首次初始化或显式恢复，不会在每次重启覆盖已轮换凭据。恢复步骤见[配置](../07-operations/configuration.md#管理员凭据与恢复)。
管理台“管理安全”页可修改密码、查看活动会话、吊销指定会话或全部会话，并按 ID 倒序分页查看审计。
密码轮换先验证当前密码，新密码须不同、至少 6 个字符且不超过 72 个 UTF-8 字节；成功后全部管理员
会话失效。logout 在服务端撤销当前会话。会话只驻留当前进程，最长 12 小时、最多 256 个，超过容量
时淘汰最早会话，服务重启后需重新登录。当前没有多管理员角色或通用权限平台。

审计保存 actor、固定动作、目标、时间、结果、HTTP 状态及固定失败分类，不写密码、token、请求正文、
查询字符串或异常正文。ban、用户凭据重置、组织变更、资产交接和管理员安全操作都通过已知路由登记。
普通管理写先落 STARTED 再执行业务；结束后记录 SUCCESS、REJECTED 或 FAILED，终态保存失败时保留
STARTED，不能据此断言业务未执行。凭据初始化、恢复、轮换及其成功审计在同一事务提交。
审计保留最多 10,000 条本实例的 PostgreSQL 记录，每页默认 50、最大 100 条；它不是外部长期审计归档。
登录鉴权豁免只匹配 Ktor 规范化后的精确 path `/api/admin/login`；query string 不影响合法登录，其他
仅以该字符串结尾的路径不能借 suffix 规则绕过 Bearer 校验。
成功登录的 token 会绑定持久凭据中的管理员用户名；管理交接收据使用该经验证 principal，不能把普通 uid
伪造成操作者。`GET /api/admin/users/{uid}/document-custody-plan` 要求显式目标 owner/steward 并返回计划
指纹，`POST /api/admin/users/{uid}/document-custody-transfer` 携带该指纹与稳定 operationId 执行。当前
不接受省略目标后猜测父部门或 leader；缺失目标返回 400，计划或 operation 冲突返回 409。

`AdminService` 只编排行政查询、领域服务和诊断端口，不直接读取 Exposed 表或文件系统。全局用户与
群聊分页、管理统计分别由 PostgreSQL 管理目录适配器实现，不借用聊天聚合 Repository 承载管理读
模型；日志与存储容量由文件诊断适配器实现并在 `ServerModule` 组装。这样 application 层不会持有
`Database`、Exposed schema 或本地目录身份，聊天领域也不依赖后台分页概念。
群分页先取稳定的一页基础行，再用一次分组聚合批量读取活跃成员数，不能随 page size 产生逐群 N+1
查询；管理群详情也由同一目录端口按 group 身份读取，不能借重复的聊天 Repository 查询绕回领域层。

管理分页使用跨 HTTP/application/adapter 的已校验值对象：`page >= 1`、`size` 为 1..100，非法值返回
400。PostgreSQL offset 使用安全的 Long 乘法；消息搜索仍受 Lucene Int offset 限制，超过边界的深分页
和 10,000 条收集预算共同限制，越界请求在分配 collector 前被明确拒绝而不是发生负数溢出。overview 在线人数由连接 owner 直接计数，不复制全部 uid；“今日事件”
按注入时钟的本地时区零点计算，默认使用服务进程系统时区。

文件诊断有固定资源预算：服务端最多返回 256 个日志；tail 最多返回 2000 行、最多反向读取 2 MiB；
如果字节预算切在一行中部，会丢弃不完整的首段。客户端遥测不按文件枚举，事件、设备和策略 API
使用有界分页；事件直接由独立的 7 日 Lucene 日志库查询，并可按 uid、deviceId、phone、平台、版本、commit、
类别、事件名和时间过滤。phone 只在 application 边界解析为 uid，不写入事件或索引。诊断策略最多
24 小时，精确设备策略优先于用户策略，过期后自动回到 BASELINE，所有启停操作都写审计。
该 Lucene 库是单实例、短保留、可丢失的诊断事实源；批次 receipt 与事件在同一次 commit 发布，PG 只承载
设备画像、策略和审计。多节点共享检索、长期留存或跨用户分析需要替换 event-store 适配器，不能把高频
事件回填到业务 PG。BASELINE 也不是客户端自报权限：路由只接受服务端审核过的结构化类别/code/state，
任意文本仅在有效 DIAGNOSTIC 策略下经服务端净化后进入全文字段。
存储容量每个根目录最多遍历 100000 个 entry；达到预算时 overview
保留现有 byte 字段作为已扫描下限，并通过 `storageScanTruncated=true` 明确标记截断。

管理、机器人和其他结构化 JSON 写接口统一采用 1 MiB 的流式硬上限：读取第一个超限字节时取消
请求体并返回 413，不能先把攻击者控制的正文完整驻留内存；语法或契约反序列化失败返回 400。
附件上传和客户端压缩遥测有各自更贴近媒体语义的独立上限，不得复用或绕过这个小请求边界。

## 6. 产品入口

客户端全局搜索提供全部、消息、联系人、文档、文件和服务分类。文件分类可筛选群文件或聊天附件，
以及图片、视频、音频和其他文件；结果的来源入口可限定到当前空间或会话。每个内容领域独立分页，
每类最多驻留 200 条摘要，全部分类只展示有界预览。服务搜索仍展示能力边界，不把未接入能力伪装为空结果。
搜索失败、没有命中和仍有下一页分别展示；空页携带游标时仍可继续加载。当前状态见[功能状态](../10-reference/feature-status.md)。

## 7. 内容与资产搜索

`contentSearch.search` 每次查询一个领域：文档、群文件或聊天附件；空 `scopeId` 表示调用者当前可见
范围，指定空间或会话时仍执行同一领域授权。文档按中文分词检索当前标题与 Markdown 正文，群文件
和聊天附件按不区分大小写的字面文件名片段匹配。文件名中的 `*`、`?` 不解释成查询语言；文件类型取
当前 MIME 的 `image/`、`video/`、`audio/` 前缀，其余归为其他文件。评论、历史正文、OCR 和附件
二进制内容不进入该检索范围。

服务端先按当前领域权限、文件名或正文和文件类型筛选索引候选，再回读当前对象。文档同时检查活动
节点、活动空间和 typed READ 权限，群文件检查活动文件条目与当前成员关系；候选的 scope、revision
和更新时间必须与当前对象一致。聊天附件复验当前消息及附件元数据摘要，再按实际附件名称与 MIME
逐项匹配。主附件包括文件、图片、语音、视频及 RichText/Reply 清单中的主对象，同一消息的相同
canonical path 只返回一条，缩略图和表情不生成独立命中。

每页默认 20 条、最多 50 条；关键词最多 1,000 字符且不含控制字符。每个请求以 64 条候选为一批，
最多检查 256 条；权限变化或旧索引造成稀疏结果时可以返回空页和继续游标，不无界补扫。文档和群文件
按更新时间倒序、对象 ID 升序分页；聊天附件按消息时间倒序、稳定消息身份与附件身份升序分页，
能够继续同一消息的剩余附件。游标绑定账号、领域、关键词、范围和类型，换条件必须从第一页开始；
页间变化不提供固定快照，客户端收到领域失效提示后重新查询。

摘要不携带 Markdown 正文或 FileStore 路径。聊天附件的 `targetId` 是 canonical path 的 SHA-256
小写 hex，必须与 `scopeId + serverSeq` 联合定位；相同文件出现在不同消息时保留不同结果。打开文档、
群文件或聊天附件前，SDK 分别重新读取文档、文件条目或精确消息并核对身份；摘要本身不授予打开或下载
权限，HTTP 下载仍由当前附件引用 ACL 裁决。

文档与群文件的权威写入在同一 PostgreSQL 事务更新 `content_search_pending` 的资源修订单槽，
索引器读取当前对象后按 revision 提交 Lucene，再精确确认对应 pending；并发新写入不会被旧确认删除。
启动以有界 PostgreSQL 扫描核对提交索引的身份、内容摘要、revision、scope、更新时间和总数，不一致时
由当前对象重建；启动恢复结束后才开放服务。运行维护和查询前继续推进 pending，每批最多读取 8 个
当前对象，单次查询最多推进 2,048 个 pending；不能收敛时返回明确
的暂不可用状态。聊天附件复用 MessageStore 原有 outbox、revision 墓碑和启动审计；消息索引 schema
为 3，旧 schema 索引由权威消息重建，消息正文与用户数据保持不变。
