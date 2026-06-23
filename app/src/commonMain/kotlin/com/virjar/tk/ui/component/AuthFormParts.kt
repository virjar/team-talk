package com.virjar.tk.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 认证表单的共享 UI 片段（登录/注册共用）。
 *
 * 抽象目的：LoginScreen 和 RegisterScreen 内部表单结构高度重复（渐变背景+Logo+圆角卡片+
 * 圆角字段+提交按钮），Desktop 的 AuthForms.kt 也复制了一份且丢了 testTag。
 * 抽出这些片段后，登录/注册页只编排布局，表单细节单点维护。
 */

/** 认证页渐变背景 + Logo 头 + 标题。 */
@Composable
fun AuthHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3370FF), Color(0xFF245BDB)))),
            contentAlignment = Alignment.Center,
        ) {
            TeamTalkLogo(size = 72.dp)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** 认证表单卡片容器（圆角 + 阴影）。 */
@Composable
fun AuthCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp), content = content)
    }
}

/** 认证表单输入字段（圆角 + testTag 语义标记）。 */
@Composable
fun AuthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    testTag: String,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
    )
}

/** 认证表单提交按钮（48dp + 圆角 + loading spinner）。 */
@Composable
fun AuthSubmitButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
    testTag: String,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag(testTag),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** 认证页错误提示文本。 */
@Composable
fun AuthError(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** 认证页底部切换链接（如"没有账号？注册"）。 */
@Composable
fun AuthSwitchLink(
    text: String,
    onClick: () -> Unit,
    testTag: String,
) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(testTag)) {
        Text(text, color = MaterialTheme.colorScheme.primary)
    }
}
