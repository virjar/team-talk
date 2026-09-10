package com.virjar.tk.server.domain.document

import com.virjar.tk.server.infra.db.AdminFeatureSettings
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 文档空间导出的后台开关。缺省关闭；由超级管理员经管理 API 翻转，
 * 同时约束空间责任人（steward）入口与超级管理员入口之外的一切未来导出路径。
 */
internal class DocumentExportPolicy(private val database: Database) {

    fun isEnabled(): Boolean = transaction(database) {
        AdminFeatureSettings.selectAll().where {
            AdminFeatureSettings.settingKey eq AdminFeatureSettings.KEY_DOCUMENT_EXPORT_ENABLED
        }.singleOrNull()?.let { it[AdminFeatureSettings.settingValue] == VALUE_ENABLED } ?: false
    }

    fun setEnabled(enabled: Boolean, updatedBy: String) {
        transaction(database) {
            AdminFeatureSettings.deleteWhere {
                AdminFeatureSettings.settingKey eq AdminFeatureSettings.KEY_DOCUMENT_EXPORT_ENABLED
            }
            if (enabled) {
                AdminFeatureSettings.insert {
                    it[settingKey] = AdminFeatureSettings.KEY_DOCUMENT_EXPORT_ENABLED
                    it[settingValue] = VALUE_ENABLED
                    it[updatedAt] = System.currentTimeMillis()
                    it[AdminFeatureSettings.updatedBy] = updatedBy.take(64)
                }
            }
        }
    }

    companion object {
        private const val VALUE_ENABLED = "true"
    }
}
