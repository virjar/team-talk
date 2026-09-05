# 文件存储

## 1. 目标

TeamTalk 把文件服务内嵌在单体中，保证私有化部署的数据所有权和消息成功语义。消息协议只引用
FileStore 相对路径；存储实现可以演进，但不能把任意第三方 URL 暴露成核心附件身份。

## 2. 分层

```text
FileStore
├── metadata / small objects → RocksDB
├── large objects            → sharded file system
└── temporary upload         → data/file-store/tmp
```

当前阈值和压缩参数以服务端 storage 代码为准。文档不把调优值当作长期协议；调整阈值不改变
Attachment。

大文件使用两级 hash 目录降低单目录文件数。物理文件名由 storage key 决定，不使用用户上传文件名，
防止路径穿越和跨平台字符问题。

FileStore 启动先在局部变量中打开 RocksDB、column family handle 和两个 tier，并在发布资源前同时扫描
`metadata`、`data`、`uploads` 三个业务 column family 与大文件目录。`PENDING_CREATE` /
`PENDING_DELETE` 会继续完成实体删除再撤销 metadata；缺实体或长度不符的活动记录会被清除，缺权威
活动记录的物理对象也会删除。
metadata 使用严格 JSON：每条记录必须显式携带 `lifecycle`，编码端也始终写出默认字段；缺失
`lifecycle`、出现未知字段或未知枚举值都会在 reconcile 前使启动失败，绝不能静默回退成 `ACTIVE`。
持久 `uploadedAt` 也必须是非负时间戳，它是未引用上传租约的权威起点，损坏记录不能进入回收判断。
任一实体无法确认删除、记录损坏或扫描失败都会让初始化失败，不能把未计量占用伪装成健康。只有
reconcile 完成且所有一次性 native option 成功关闭后，才以 `db` 作为最后一个可见运行标志发布整组
资源。任一启动或 option 关闭失败都会逆序尝试关闭全部已获取 handle 和数据库；取消与 VM fatal error
在 drain 完成后
保持原对象优先传播，普通错误作为 suppressed cause 保留。数据库使用会报告原生关闭错误的
`closeE`，不走 RocksDB 默认静默忽略异常的 `close`；健康检查因此不会观察到半初始化存储。若启动
本身失败但全部清理成功，同一实例仍可重试；任一 option、启动回滚或运行期关闭失败都会把实例固定为
不可恢复终态，后续 `init`/`close` 精确重放同一异常对象且不再触碰 native 资源。

## 3. 上传

`POST /api/v1/files/upload` 使用 multipart 和 access token。服务端流程：

1. 校验 Bearer、`Idempotency-Key` canonical UUID、`X-TeamTalk-Command-Issued-At` canonical epoch
   millisecond，以及严格 multipart envelope。
2. 只解析唯一 file part 的 headers，从外层 `Content-Length`、已经消费的 framing 和固定终止边界推导
   精确 payload 长度；在创建临时文件或复制正文前，让 FileStore 持久化 `STARTED` 并预留主文件容量。
3. 按精确长度把正文流式写入受管临时文件，同时计算一次 SHA-256；完整消费固定终止边界后才得到 staged
   payload。指纹以长度分帧编码版本、认证 uid、uploadId、issuedAt、文件名、规范 MIME、长度和摘要。
4. 图片/视频在源临时文件仍存在时，通过受限 helper JVM 提取媒体元数据和可选缩略图；缩略图在持久化前
   另行预留其精确字节和对象槽。
5. 按分层策略持久化主文件与可选缩略图，metadata 用事务 key、attempt token 和对象序号声明它们属于
   本次上传；随后编码一次权威 `UploadResult` JSON。
6. 确认输入、helper 输出等临时文件已退休后，在 `uploads` column family 把指纹、两项以内的 descriptor
   和原始 JSON 收据同步提交为 `COMPLETED`，再尝试交付 HTTP 200。
7. 首次交付和精确重放都从 durable complete/replay begin 到 HTTP response 完成持有一个短暂进程内
   delivery pin；finally 关闭事务 owner、pin 与全部临时资源。

上传 framing 必须有唯一的外层 `Content-Length`，拒绝 `Transfer-Encoding`；只接受名为 `file` 的一个
part，part 的 `Content-Length` 必须与上述推导长度精确相等。payload 由长度而不是 delimiter 扫描界定，
所以文件内容可以包含形似 boundary 的字节，而额外 part 会表现为长度/framing 不一致并返回 400。这样路由
在正文到达前已知精确容量，不需要先把不确定大小的请求落盘再补计量。

FileStore 容量账本把 `stored` 与 `pending` 分开统计，但准入使用二者之和。主文件的 pending bytes 和一个
对象槽在 `STARTED` 写入前后同一临界区取得，物化成功后原样转为 stored，不做第二次容量决策；0B 仍占
对象槽。可选缩略图沿用同一规则。在 durable `COMPLETED` 之前，暂存、helper、对象持久化、元数据读取、
响应编码或临时文件退休任一步失败，都会关闭 `STARTED` owner 并逆序回滚已经物化的对象：RocksDB tier
在同一 batch 删除 metadata 与 payload；文件系统 tier 先同步写 `PENDING_DELETE`，确认实体删除后才删除
metadata 和扣减容量。任一步清理无法确认时保留恢复事实与容量占用、把 FileStore 健康置为失败。

`uploads` column family 以认证 uid + uploadId 保存严格 JSON 的 `STARTED` / `COMPLETED` 状态。
`STARTED` 保存 attempt、指纹（正文完成后绑定）、预留尺寸和已经物化的路径；对应 FileMetadata 保存
transaction key、attempt token 与 main/thumbnail 序号。启动 reconcile 会删除所有未完成 attempt 的
RocksDB/文件系统对象并在确认后删除 `STARTED`，不会把崩溃中间态恢复成成功。`COMPLETED` 同时保存
规范请求指纹、主文件/缩略图 descriptor、完成时间、收据租约与即将返回的原始 JSON；启动时必须逐项
核对其 ACTIVE metadata 和物理实体。

完成记录在首次 HTTP delivery 之前 durable，所以提交后的响应编码不再发生，响应发送失败也不回滚
对象。同一 uid/uploadId 的重试仍完整 stage/hash 正文，只有指纹完全一致才原样返回持久 JSON；在
identity 时间仍可接受时，改写 issuedAt、文件名、MIME、长度或内容返回 409。活跃的 `STARTED` 或仍在
HTTP delivery pin 中的同 ID 返回 409 + `Retry-After`，过期 identity/收据返回 410，owner/global 容量
拒绝返回 507。delivery pin 只保护正在交付的一个收据，避免到期维护在响应窗口拆除 backing；它不是
通用任务队列或客户端重试器。

收据租约到期且没有 delivery pin 后，维护在同一 RocksDB batch 删除完成记录并清空对象 metadata 上的
上传事务 ownership；对象本身继续按业务引用与未引用 TTL 决定保留或退休，不把 uploadId 变成永久存储
身份。

对外返回上传成功前还有一条主机崩溃耐久边界：RocksDB tier 的 metadata/payload batch 使用启用 WAL
且 `sync=true` 的写入；文件系统 tier 则先用同步 WAL 写入并计量 `PENDING_CREATE`，再强制刷出实体文件
及从分片目录到存储根的目录项，最后用同步 WAL 把记录切换为 `ACTIVE`。首次 move 后即使活动 metadata
写入失败，durable pending 仍能让本进程或下次启动删除实体；只有 `ACTIVE` 可用于发送和下载。

生产请求不会在服务端主 JVM 中运行 ImageIO/JavaCV/JNI。缩略图 helper 使用非排队的固定并发门禁
（默认 2，代码硬上限 4）和 15 秒硬超时；饱和、超时、非零退出或结果校验失败时直接省略缩略图，
原附件仍可成功。超时进程先终止再强制终止；若仍无法证明已经退出，对应 helper 名额不会重新借出，
避免失控子进程突破并发上限。

主进程预创建并拥有输出与结果临时文件，helper 不通过 stdout 返回路径。结果协议为固定长度，版本、
状态、尺寸、时长、输出长度和 SHA-256 都有严格边界；主进程还复验 JPEG 首尾标记、长度和摘要。
helper 的环境不继承服务端 secret，日志关闭，堆、direct memory 和处理器数受 JVM 参数限制。无论失败
发生在哪一步，结果文件和未交接缩略图都在 finally 中退休；不能确认退休时仍按受管临时残留失败关闭。
一旦缩略图已经进入持久化阶段，存储失败则属于整个上传失败，不能留下只有主文件的未发布孤儿。

上传暂存、FileStore 的 InputStream 暂存、helper 结果和缩略图输出全部位于显式受管的
`data/file-store/tmp`：目录拒绝符号链接，在 POSIX 文件系统上收紧为仅 owner 可访问，临时文件也只给
owner 读写，并由请求或调用方的 `finally` 删除。图片在 ImageIO 解码前先从 ImageReader 元信息验证
单边尺寸与总像素硬预算；预算内的大图按解码像素预算设置 source subsampling，越界图不进入全量解码。
视频在 `grabImage` 前以同一宽高/像素边界拒绝异常帧元数据。

普通附件同时受两层集中硬配额约束：全局默认最多 10 GiB、100,000 个对象，每个 `FileMetadata.uid`
默认最多 2 GiB、20,000 个对象；测试构造器可以注入更小边界。两层对象数都会计入 0B 文件，所以空文件
不能绕过 owner 或全局资源预算。该 owner 配额只以已认证上传者和持久 metadata 为事实源，用于防止一个
账号先耗尽共享 FileStore；它不是尚未建立的组织计费或管理员存储策略。

运行期所有 store/rollback 在同一写锁内 O(1) 同步增减 global 与 owner 两套字节/对象账本；并发准入会
同时占用两层最后一个槽。`PENDING_CREATE`、`ACTIVE` 与 `PENDING_DELETE` 在实体确认消失前都占用原
owner 和全局容量，只有 metadata 删除成功后才一起扣减；RocksDB 或文件系统写入结果不确定且清理失败
时保留两层占用并把实例健康置为失败，不能把幽灵容量再次借出。删除按 metadata 中的 owner 释放，不从
请求参数或 path 猜测归属。内部测试只可按指定 owner 读取不含身份的 bytes/files 投影，健康与公开错误
不枚举 uid、文件名或账本明细。容量耗尽返回 HTTP 507，正文只区分 `owner` 或 `global` 类别。

启动 reconcile 完成前不开放准入：metadata 通过 RocksDB iterator 流式处理，逐条校验 key、uid、size、
路径归属和加法溢出，并从剩余权威记录重建 global 与 per-uid usage；owner map 的基数天然不超过全局对象
上限。活动对象集合也由同一对象数上限约束，RocksDB orphan 只按固定小批次保留待删 key。扫描一旦发现
全局或任一 owner 的历史活动 metadata 超界、记录损坏或状态无法收敛，FileStore 保持未发布并启动失败，
不会截断账本、忽略超限 owner 或用空账本继续服务。重启因此只会在 pending 实体及 metadata 已确认清理
后释放两层容量，不会重置配额。

## 4. 业务引用校验

消息、群文件、文档和用户头像都不信任客户端 sidecar。引用一个附件前共同执行：

- canonicalize path。
- 查询元数据确认主文件和缩略图存在。
- 校验 name/contentType/size 与权威元数据。
- 对 Markdown 上下文还要校验正文内部 URI 与 canonical sidecar 精确闭包，不允许未声明、未引用或重复资产。

消息引用的额外边界是：

- 发送者可使用自己未业务绑定的 staging 上传，或仍通过一个已引用该附件的活跃 Chat/可读文档空间获得
  业务使用权；后者支持合法转发，但不会把“曾是上传者”当成永久授权。
- 消息服务另行校验发送者是目标 Chat 成员。
- Message、幂等索引、附件到 Chat 的反向索引和待投影 outbox 在同一 RocksDB batch 中提交。

文档引用故意更严格：

- 对目标文档而言的新资产必须是调用者本人的未绑定 staging 上传。
- 非 staging 资产只能在 assetId 和权威描述符都已出现于同一目标文档的当前或历史修订时复用。对其他消息、
  群文件或文档拥有读权，不代表可将对应 path 重新绑定到本文档。新建文档没有历史已知集合。
- PostgreSQL 文档事务保存修订区间资产清单；主文件、缩略图、顺序与首次/最后 revision 是历史快照的
  一部分，不从当前清单反推旧修订。
- 当次 staging 资产在业务事务提交前先单调标记 `businessBound`。后续 revision 冲突或 PostgreSQL 失败可留下无引用、
  等待 TTL 回收的保守孤儿，但不得回退 uploader 旁路造成越权窗口。
- 全部通过后才返回对应的消息 ACK 或文档 RPC 成功响应。

用户头像是另一条小型引用边界：只允许本人尚未业务绑定的 staging 主对象，描述符必须与 FileStore
metadata 完全一致，MIME 只允许 `image/jpeg`、`image/png`、`image/webp`，大小最多 8 MiB。
头像与消息/文档不同：它本来就向认证用户公开，因此服务端先提交 PostgreSQL 当前头像引用与事件，
再单调标记 business-bound。PostgreSQL 失败时对象仍为 staging，可安全重试；后续发布失败时当前头像
引用已经授权并防止回收，精确重试会补齐发布但不重写头像。替换或清除先修复当前 staging 头像的发布，
使更旧请求随后只能看到“已绑定且非当前”并被拒绝。联系人卡片中的头像只是显示快照，不新增永久附件引用。

SDK 结构校验只是第一层，不能替代 FileStore 查询。

## 5. 下载

`GET /api/v1/files/{path...}` 必须携带 Bearer access token。服务端首先校验 token，再允许以下用户
读取：

- FileMetadata 记录的上传者，且对象仍为未业务绑定的 staging；用于上传完成、尚未提交时的预览。
- path 仍是任一用户当前资料头像；任意已认证用户都可读取，以支持资料、好友和单聊投影展示。
- 当前仍属于至少一个引用该 path 的 Chat 的成员。
- 对至少一篇引用该 path 的活动文档拥有实时 `READ` 或更高空间权限。

附件的反向引用来自当前 User 头像列、消息索引、群文件版本表和文档修订资产表。消息引用随消息原子写入；群文件引用随文件版本、
命令收据、容量台账与审计在同一事务写入。退出或移除成员后无需修改附件记录，实时成员查询会立即
拒绝新下载；文档读取同样每次重算空间 ACL，不把反向引用当作授权缓存。头像替换或清空后旧 path
立即失去这条认证用户读取权，除非仍有其他实时业务 ACL。路径由 uid 与随机标识组成，
但随机性不参与授权。服务端根据元数据定位 RocksDB 或
文件系统并流式返回。响应应包含正确的 Content-Type、Content-Length、缓存与下载文件名策略。
下载授权不会先物化某个热门 path 的全部引用会话：PostgreSQL 先以每用户 1,000 个 Conversation 容量
多取一行并 fail closed，再让这个有界授权集合分别与 RocksDB 消息反向索引和群文件 SQL 做存在性交集。
组织受管群的异常投影也只在该用户的有界集合中查询，不能全表装载后再过滤。

底层两种存储后端共享经过校验的 inclusive `ReadRange` 到 `(offset, length)` 转换；越过对象尾部统一
截断为空或剩余字节。适配 Ktor channel 时必须再把切片转换为排他的 end index，不能把 length 误作
end index。HTTP 下载入口在 Bearer 与实时业务 ACL 都通过后解析单段 `bytes` Range，支持
`start-end`、`start-` 和 `-suffixLength`，并按同一流式读取链返回 `206`、`Content-Range` 与精确
`Content-Length`；没有 Range 时返回 `200`。多段、畸形、零字节对象 Range 和完全越过对象尾部的
范围返回不含正文的 `416` 与 `Content-Range: bytes */size`。若末字节超过对象尾部但首字节仍有效，
则按 RFC 7233 截断到对象尾部。Range 解析不能早于授权，否则 `416` 响应会向无权限调用者泄露对象
大小；每个新 Range 请求都重新执行权威 ACL。该能力用于协议兼容和未来的可恢复下载，正式客户端的
视频播放器不直接消费 HTTP Range，而是在完整下载并原子发布本地缓存后播放。

该索引没有自动为缺少反向引用的旧消息回填。遇到这类历史布局时必须保留 MessageStore 与文件实体，
先提供并验收从权威消息重建反向索引的迁移工具，再开放相关访问；不能把普通升级变成清空消息后
重新发送。存储版本与数据保留规则见[持久化生命周期](persistence.md#6-schema-epoch-与生命周期)。

## 6. 群文件引用

上传成功只代表 FileStore 对象可用，不会自动进入共享目录。GroupFileService 发布时再次确认：

- 目标是群聊且调用者仍是成员；
- 调用者是该 Attachment 的上传者；
- path、name、contentType、size 与 FileStore 完全一致；
- 新版本不会超过群空间字节配额或每文件 128 个活动版本；零字节版本也占版本槽。

群文件版本表提供 Attachment 到 Chat 的反向引用。文件条目软删除后，该引用不再授权下载；如果同一
附件仍被消息或另一个活跃群文件引用，对应 Chat 的当前成员仍可访问。

## 7. 引用保留与物理回收

上传响应成功不等于形成业务引用。未进入当前用户头像、消息、活动群文件或活动文档修订的对象从 `uploadedAt` 起持有默认 168 小时
租约，配置 `TEAMTALK_UNREFERENCED_ATTACHMENT_TTL_HOURS` 可在 1–8760 小时内调整。小时级维护每次只
扫描固定页数、每页固定条数的 metadata keyspace，并批量查询 MessageStore 与 PostgreSQL 当前头像、群文件/文档修订引用；有任一权威引用就
保留，零引用才进入 FileStore 已有的 `PENDING_DELETE`/原子 RocksDB 删除协议。消息撤回会原子移除
附件反向索引，群文件条目删除会让其全部历史版本退出活动引用；共享 path 只有最后一个引用消失后才
能被物理回收。

上传者旁路只用于业务绑定前的预览/提交窗口。FileStore metadata 一旦观察到任一用户头像、Chat、群文件或文档
业务引用，`businessBound` 单调置位，之后连原上传者也必须通过当前业务 ACL。即使所有引用后续消失，
该标记也不回退，避免离职或撤权的旧上传者凭历史 owner 身份继续下载已绑定资产。

引用写入和退休决策跨越三个存储，不能假装存在分布式事务。当前单实例运行时用一个 Application-owned
固定容量的分片协程围栏按附件路径串行化“最终附件校验 → 业务引用提交/绑定标记”和
“重新读取全部权威引用 → 物理退休”。引用提交完成后崩溃，重启扫描能看到 durable reference；业务提交前崩溃时，未绑定对象
保持 staging，已单调绑定但尚无引用的对象则保守地等待租约回收，两者都不会形成虚假业务 ACL。
FileStore 删除本身继续使用同步 WAL、filesystem tombstone 与启动 reconcile，因此容量只在 metadata
确认删除后归还。不同分片可以并行，但多路径操作按分片序号加锁以避免死锁。未来多实例化必须把这一围栏替换为共享租约/事务 outbox，不能复制进程内 Mutex 假装
互斥。

## 8. 客户端策略

- 缩略图和小文件可以自动缓存。
- 大文件点击后下载，普通附件消息气泡显示下载进度和取消/重试。这不代表 Markdown 内嵌资产的上传 job
  已提供就地重试/取消；阶段一仍需删除失败引用后重新选择。
- 所有实际网络读取都携带当前会话 access token；图片、音视频加载器也不能例外。
- 下载完成后从本地路径打开，不重复请求。
- 媒体在应用内播放；普通文件可交给系统默认应用。
- 缓存淘汰只删除客户端副本，不改变消息 Attachment。

普通 Desktop/Android GUI 上传每次用户操作生成新的 identity，当前不会在应用层自动重试。SDK 为需要
可靠恢复的调用方保留显式 identity 入口，但调用方必须同时持有完全相同的源字节；跨进程源文件 spool、
持久上传 outbox、取消和就地重试统一由 Roadmap `CLIENT-04` 落地，不能由 transport 隐式重传替代。

下载和播放继续本地优先：认证下载完整对象，按 `Attachment.size` 校验并原子发布进账号隔离的有界缓存，
播放器只打开本地路径。服务端 Range 只提供下载协议能力，不把视频、音频或图片改成默认在线播放。

## 9. 运维

`data/file-store/` 是持久数据，部署同步不能覆盖。备份需要同时包含元数据 RocksDB 与大文件目录；
只备份其中一层会产生不可解析附件。

需要监控临时目录、总容量、写失败、reconcile 修复计数、未引用回收数量/失败、FileStore 健康、
缩略图失败和下载错误率。
文件系统 metadata 在实体 move 前已经持久化，所以启动扫描看到的无 metadata 物理对象不是“上传后尚未
发送”的正常对象，可以确定性删除；删除无法确认时必须保持实例不可用，不能人工先删 metadata 或改小
容量计数来绕过健康失败。
