package com.virjar.tk.server.infra.db

import com.virjar.tk.server.domain.document.DocumentExportGate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** admin_feature_settings 表的访问实现；目前承载文档空间导出开关。 */
internal class AdminFeatureSettingsStore(private val database: Database) : DocumentExportGate {

    override fun isEnabled(): Boolean = transaction(database) {
        AdminFeatureSettings.selectAll().where {
            AdminFeatureSettings.settingKey eq AdminFeatureSettings.KEY_DOCUMENT_EXPORT_ENABLED
        }.singleOrNull()?.let { it[AdminFeatureSettings.settingValue] == VALUE_ENABLED } ?: false
    }

    override fun setEnabled(enabled: Boolean, updatedBy: String) {
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
