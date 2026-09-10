package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table

/**
 * 管理员后台功能开关。值统一为字符串 "true"/"false"；
 * 缺失行 = 该功能的默认关闭状态。
 */
internal object AdminFeatureSettings : Table("admin_feature_settings") {
    const val KEY_DOCUMENT_EXPORT_ENABLED = "document_export_enabled"

    val settingKey = varchar("setting_key", 64)
    val settingValue = varchar("setting_value", 32)
    val updatedAt = long("updated_at")
    val updatedBy = varchar("updated_by", 64)

    override val primaryKey = PrimaryKey(settingKey)
}
