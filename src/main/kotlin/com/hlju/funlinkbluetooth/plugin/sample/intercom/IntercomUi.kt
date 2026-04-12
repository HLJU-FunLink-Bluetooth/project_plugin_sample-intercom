package com.hlju.funlinkbluetooth.plugin.sample.intercom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hlju.funlinkbluetooth.core.plugin.api.support.LogHelper
import com.hlju.funlinkbluetooth.core.plugin.api.support.PluginPageLayout
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun hasRecordAudioPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun IntercomUi(
    plugin: IntercomPlugin,
    state: IntercomState,
    onStartClick: (Context) -> Unit,
    onStopClick: () -> Unit
) {
    val context = LocalContext.current

    var hasRecordPermission by remember(context) {
        mutableStateOf(hasRecordAudioPermission(context))
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            onStartClick(context)
        } else {
            LogHelper.appendLog(plugin.eventLogs, "--- 未授予录音权限，无法开始对讲 ---")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            state.incomingStreamPlaybacks.keys.toList().forEach { payloadId ->
                val session = state.incomingStreamPlaybacks.remove(payloadId)
                session?.stopRequested?.set(true)
                try { session?.inputStream?.close() } catch (_: Exception) {}
                session?.worker?.interrupt()
            }
        }
    }

    PluginPageLayout(
        title = "实时对讲",
        eventLogs = plugin.eventLogs,
        emptyMessage = "点击开始对讲按钮启动语音实时传输",
        headerExtra = {
            Text(
                text = if (state.isStreaming.value) "对讲中" else "待机",
                style = MiuixTheme.textStyles.footnote2,
                color = if (state.isStreaming.value) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onBackgroundVariant
                }
            )
        }
    ) {
        Button(
            onClick = {
                if (state.isStreaming.value) {
                    onStopClick()
                } else if (hasRecordPermission) {
                    onStartClick(context)
                } else {
                    recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Text(text = if (state.isStreaming.value) "结束对讲" else "开始对讲")
        }

        Text(
            text = if (state.isStreaming.value) {
                "语音流实时传输中。"
            } else {
                "连接设备后即可开始对讲。"
            },
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant
        )
    }
}
