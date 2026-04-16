# 群组权限矩阵设计

> TeamTalk 群组角色与权限体系：角色定义、权限矩阵、操作约束。
>
> 设计约束：面向中小型组织（群组通常 <200 人），角色和权限体系保持简单实用，不引入复杂的权限继承和自定义角色。

---

## 1. 角色定义

TeamTalk 群组采用三级角色模型（参考 Telegram）：

| 角色 | code | 说明 | 人数限制 |
|------|------|------|---------|
| OWNER | 0 | 群主（创建者） | 每群恰好 1 人 |
| ADMIN | 1 | 管理员（由群主任命） | 每群最多 10 人 |
| MEMBER | 2 | 普通成员 | 不限 |

### 1.1 角色存储

当前 `channel_members` 表已有 `role` 字段（VARCHAR(20)），迁移为整数编码：

```sql
ALTER TABLE channel_members ALTER COLUMN role TYPE INT USING (
  CASE role
    WHEN 'owner' THEN 0
    WHEN 'admin' THEN 1
    ELSE 2
  END
);
```

Kotlin 定义：

```kotlin
enum class MemberRole(val code: Int) {
    OWNER(0),
    ADMIN(1),
    MEMBER(2);

    companion object {
        fun fromCode(code: Int) = entries.first { it.code == code }
    }
}
```

### 1.2 角色变更规则

| 操作 | 谁能执行 | 说明 |
|------|---------|------|
| OWNER → ADMIN | 仅 OWNER（转让群主） | 原 OWNER 降为 ADMIN，新 OWNER 升为 OWNER |
| MEMBER → ADMIN | OWNER 或 ADMIN | 任命管理员 |
| ADMIN → MEMBER | 仅 OWNER | 取消管理员 |
| OWNER 退出群 | 不允许 | 必须先转让群主或解散群 |

> **转让群主**：OWNER 选择一个成员，将角色互换。需要确认对话框。

---

## 2. 权限矩阵

### 2.1 完整权限矩阵

| 操作 | OWNER | ADMIN | MEMBER | 说明 |
|------|-------|-------|--------|------|
| **群信息** | | | | |
| 修改群名 | ✅ | ✅ | ❌ | |
| 修改群头像 | ✅ | ✅ | ❌ | |
| 修改群公告 | ✅ | ✅ | ❌ | |
| 修改群简介 | ✅ | ✅ | ❌ | |
| 解散群组 | ✅ | ❌ | ❌ | 仅群主 |
| 转让群主 | ✅ | ❌ | ❌ | 仅群主 |
| **成员管理** | | | | |
| 邀请成员 | ✅ | ✅ | ✅ | 所有成员可邀请（可通过群设置关闭） |
| 踢出成员 | ✅ | ✅（仅 MEMBER） | ❌ | ADMIN 不能踢 ADMIN 或 OWNER |
| 任命/取消管理员 | ✅ | ❌ | ❌ | 仅群主 |
| 转让群主 | ✅ | ❌ | ❌ | 仅群主 |
| **消息操作** | | | | |
| 发送消息 | ✅ | ✅ | ✅ | 受群禁言约束 |
| 撤回自己的消息 | ✅ | ✅ | ✅ | 2 分钟内 |
| 撤回他人消息 | ✅ | ✅ | ❌ | 管理员可撤回普通成员消息 |
| 置顶消息 | ✅ | ✅ | ❌ | |
| 编辑自己的消息 | ✅ | ✅ | ✅ | |
| **群设置** | | | | |
| 禁言指定成员 | ✅ | ✅ | ❌ | |
| 全员禁言 | ✅ | ✅ | ❌ | |
| 修改邀请权限 | ✅ | ❌ | ❌ | 控制普通成员是否能邀请 |
| 修改最大成员数 | ✅ | ❌ | ❌ | |

### 2.2 ADMIN 的操作边界

ADMIN 拥有大部分管理权限，但有几个关键限制：

1. **不能操作 OWNER 或其他 ADMIN**：只能对 MEMBER 执行踢出、禁言等操作
2. **不能修改群的核心设置**：邀请权限、最大成员数等由 OWNER 控制
3. **不能解散/转让群**：这是 OWNER 的专属权限

---

## 3. 群禁言设计

### 3.1 禁言类型

| 类型 | 说明 | 存储 |
|------|------|------|
| 单人禁言 | 指定成员不能发送消息 | `channel_member_mutes` 表 |
| 全员禁言 | 所有 MEMBER 不能发送消息（ADMIN/OWNER 豁免） | `channels.muted_all BOOLEAN` |

### 3.2 禁言数据模型

```sql
-- 新增：成员禁言表
CREATE TABLE channel_member_mutes (
    channel_id  VARCHAR(64) NOT NULL,
    member_uid  VARCHAR(64) NOT NULL,
    muted_by    VARCHAR(64) NOT NULL,    -- 操作者 UID
    muted_at    BIGINT NOT NULL,          -- 禁言时间 (epoch ms)
    duration    BIGINT NOT NULL DEFAULT 0, -- 持续时间 (秒), 0=永久
    PRIMARY KEY (channel_id, member_uid)
);

-- 修改：channels 表新增字段
ALTER TABLE channels ADD COLUMN muted_all BOOLEAN DEFAULT FALSE;
```

### 3.3 禁言检查逻辑

```kotlin
fun canSendMessage(memberRole: MemberRole, isMutedAll: Boolean, memberMute: MemberMute?): Boolean {
    // OWNER 和 ADMIN 不受任何禁言限制
    if (memberRole == MemberRole.OWNER || memberRole == MemberRole.ADMIN) return true
    // 全员禁言
    if (isMutedAll) return false
    // 单人禁言（检查是否过期）
    if (memberMute != null) {
        if (memberMute.duration == 0L) return false  // 永久禁言
        val expireAt = memberMute.mutedAt + memberMute.duration * 1000
        return System.currentTimeMillis() > expireAt
    }
    return true
}
```

### 3.4 禁言通知

禁言操作通过系统消息通知群成员：

| 操作 | PacketType | payload |
|------|-----------|---------|
| 单人禁言 | MEMBER_MUTED(95) | { channelId, memberUid, duration, operatorUid } |
| 解除禁言 | MEMBER_UNMUTED(96) | { channelId, memberUid, operatorUid } |
| 全员禁言开启 | CHANNEL_UPDATED(91) | { channelId, field: "muted_all", oldValue: "false", newValue: "true" } |

被禁言的成员尝试发送消息时，服务端返回 `SENDACK(code=3, NO_PERMISSION)`。

---

## 4. 服务端权限检查

### 4.1 检查点

每个需要权限的操作，服务端在业务处理前统一检查：

```kotlin
// 伪代码
fun requireRole(channelId: String, uid: String, minRole: MemberRole) {
    val member = memberDao.findByChannelAndUid(channelId, uid)
        ?: throw ApiException(40002, "非频道成员")
    if (member.role > minRole.code) {
        throw ApiException(minRole.name + " 权限不足")
    }
}

fun requireOwner(channelId: String, uid: String) = requireRole(channelId, uid, MemberRole.OWNER)
fun requireAdmin(channelId: String, uid: String) = requireRole(channelId, uid, MemberRole.ADMIN)
```

### 4.2 API 权限映射

| API | 最低角色 |
|-----|---------|
| PUT /api/v1/channels/{id} | ADMIN |
| DELETE /api/v1/channels/{id} | OWNER |
| POST /api/v1/channels/{id}/members/{uid}/role | OWNER |
| DELETE /api/v1/channels/{id}/members/{uid} | ADMIN |
| POST /api/v1/channels/{id}/mute | ADMIN |
| PUT /api/v1/channels/{id}/announcement | ADMIN |

---

## 5. 成员变更系统消息

角色变更和禁言操作通过已在 [01-message-model.md](./01-message-model.md) §5 中定义的系统消息 PacketType 推送：

| PacketType | 场景 | payload 字段 |
|-----------|------|-------------|
| MEMBER_ROLE_CHANGED(97) | 角色 MEMBER→ADMIN 或 ADMIN→MEMBER | { channelId, memberUid, oldRole, newRole, operatorUid } |
| MEMBER_MUTED(95) | 单人禁言 | { channelId, memberUid, duration, operatorUid } |
| MEMBER_UNMUTED(96) | 解除禁言 | { channelId, memberUid, operatorUid } |
| CHANNEL_UPDATED(91) | 全员禁言开关 | { channelId, field: "muted_all", ... } |

这些 PacketType 已在消息模型中定义但标记为 ❌ 待实现。本设计文档为其提供了具体的业务逻辑定义。

---

## 6. 与 Telegram 的对比

| 维度 | Telegram | TeamTalk |
|------|----------|----------|
| 角色层级 | 5 级（Owner/Admin/Editor/Moderator/Member） | 3 级（Owner/Admin/Member） |
| 自定义角色 | 不支持 | 不支持 |
| 权限粒度 | 精细（每项独立开关） | 预设（按角色绑定） |
| 群禁言 | 支持（永久/定时） | 支持（永久/定时） |
| 适合规模 | 百万人大群 | <200 人工作群 |

TeamTalk 选择 3 级角色 + 预设权限的原因：中小型组织的群管理需求简单，多数群只有几十人，5 级角色和细粒度权限配置增加了理解和操作成本，收益不大。
