package com.azurpilot.ghio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.AppTokens

/**
 * 敏感操作保护遮罩门
 * Sensitive operation authentication gate
 *
 * 当页面处于敏感保护且尚未解锁时展示锁屏提示界面，阻断未授权查看或操作；
 * 点击解锁按钮即可调起系统原生生物识别/锁屏 PIN 码认证。
 * Displays a locked overlay preventing unauthorized viewing or interaction
 * until unlocked via native biometrics or screen lock credentials.
 */
@Composable
fun SensitiveAuthGate(
    isUnlocked: Boolean,
    onUnlockRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (isUnlocked) {
        content()
    } else {
        var hasAutoPrompted by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(isUnlocked) {
            if (!isUnlocked && !hasAutoPrompted) {
                hasAutoPrompted = true
                onUnlockRequest()
            }
        }

        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(AppTokens.Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(AppTokens.Spacing.lg))
                Text(
                    text = stringResource(R.string.auth_gate_locked_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(AppTokens.Spacing.sm))
                Text(
                    text = stringResource(R.string.auth_gate_locked_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(AppTokens.Spacing.xl))
                Button(onClick = onUnlockRequest) {
                    Text(text = stringResource(R.string.auth_gate_unlock_button))
                }
            }
        }
    }
}
