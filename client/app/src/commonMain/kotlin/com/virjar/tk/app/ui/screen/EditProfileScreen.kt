package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.component.SettingsGroupCard
import com.virjar.tk.app.ui.component.SettingsPrimaryButton
import com.virjar.tk.app.ui.component.SettingsSectionLabel
import com.virjar.tk.app.ui.component.TkFormTextField
import com.virjar.tk.app.ui.theme.Tk
import kotlinx.coroutines.launch

internal fun editProfileBackAction(
    onBack: (() -> Unit)?,
    saving: Boolean,
    avatarBusy: Boolean,
): (() -> Unit)? = onBack?.takeUnless { saving || avatarBusy }

internal data class EditProfileDraftField(
    val value: String,
    val edited: Boolean = false,
) {
    fun edit(next: String): EditProfileDraftField = copy(value = next, edited = true)

    fun rebase(authoritative: String?): EditProfileDraftField =
        if (edited) this else copy(value = authoritative.orEmpty())
}

internal fun profileFormReady(currentUser: User?): Boolean =
    currentUser?.revision?.let { it > 0L } == true

@Composable
fun EditProfileScreen(
    currentUser: User?,
    onSave: suspend (name: String, phone: String?) -> Boolean,
    avatarEditState: ProfileAvatarEditState = ProfileAvatarEditState(),
    onChooseAvatar: (() -> Unit)? = null,
    onRemoveAvatar: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(EditProfileDraftField(currentUser?.name.orEmpty())) }
    var phone by remember { mutableStateOf(EditProfileDraftField(currentUser?.phone.orEmpty())) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val profileReady = profileFormReady(currentUser)

    LaunchedEffect(currentUser?.revision, currentUser?.name, currentUser?.phone) {
        name = name.rebase(currentUser?.name)
        phone = phone.rebase(currentUser?.phone)
    }

    val busy = saving || avatarEditState.busy

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "编辑资料",
            onBack = editProfileBackAction(onBack, saving, avatarEditState.busy),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 头像区：居中预览 + 次级操作 ──
            val preview = avatarEditState.preview
            if (preview != null && avatarEditState.hasReplacement && !avatarEditState.removeRequested) {
                Image(
                    bitmap = preview,
                    contentDescription = "待保存的新头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(Tk.avatarShape(88.dp))
                        .testTag("profile.avatar.preview"),
                )
            } else {
                AvatarPlaceholder(
                    name = currentUser?.name,
                    avatar = currentUser?.avatar.takeUnless { avatarEditState.removeRequested },
                    size = 88,
                    modifier = Modifier.testTag("profile.avatar.preview"),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onChooseAvatar != null) {
                    TextButton(
                        onClick = onChooseAvatar,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.testTag("profile.avatar.pick"),
                    ) {
                        Text(
                            if (currentUser?.avatar != null || avatarEditState.hasReplacement) "更换头像" else "选择头像",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                val canRemove = currentUser?.avatar != null || avatarEditState.hasReplacement
                if (onRemoveAvatar != null && canRemove && !avatarEditState.removeRequested) {
                    TextButton(
                        onClick = onRemoveAvatar,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.testTag("profile.avatar.remove"),
                    ) { Text("移除头像", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) }
                }
            }
            val avatarStatus = when {
                avatarEditState.processing -> "正在生成方形头像…"
                avatarEditState.uploadProgress != null ->
                    "正在上传处理后的头像 ${(avatarEditState.uploadProgress!!.coerceIn(0f, 1f) * 100).toInt()}%"
                avatarEditState.removeRequested -> "保存后将移除当前头像"
                avatarEditState.hasReplacement ->
                    "已生成不超过 ${PROFILE_AVATAR_OUTPUT_SIZE}×${PROFILE_AVATAR_OUTPUT_SIZE} 的方形头像"
                else -> "选择图片后会居中裁剪为方形并限制输出尺寸"
            }
            Text(
                text = avatarStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("profile.avatar.status"),
            )
            avatarEditState.errorMessage?.let { avatarError ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = avatarError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("profile.avatar.error"),
                )
            }
            if (!profileReady) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "正在读取最新资料…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("profile.loading"),
                )
            }

            Spacer(Modifier.height(16.dp))
            // ── 基本资料卡片 ──
            SettingsSectionLabel(
                "基本资料",
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsGroupCard {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TkFormTextField(
                        value = name.value,
                        onValueChange = { name = name.edit(it) },
                        label = "显示名",
                        tag = "profile.name",
                    )
                    TkFormTextField(
                        value = phone.value,
                        onValueChange = { phone = phone.edit(it) },
                        label = "手机号",
                        tag = "profile.phone",
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))
            val progress = avatarEditState.uploadProgress
            val saveLabel = when {
                progress != null -> "上传头像 ${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                saving -> "保存中…"
                else -> "保存"
            }
            SettingsPrimaryButton(
                text = saveLabel,
                onClick = {
                    scope.launch {
                        saving = true
                        error = null
                        try {
                            val success = onSave(name.value, phone.value.ifBlank { null })
                            if (success) onBack?.invoke() else error = "保存失败，请重试"
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = profileReady && name.value.isNotBlank() && !busy,
                tag = "profile.save",
            )
        }
    }
}
