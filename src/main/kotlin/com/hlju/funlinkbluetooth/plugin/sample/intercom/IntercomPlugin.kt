package com.hlju.funlinkbluetooth.plugin.sample.intercom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.hlju.funlinkbluetooth.core.plugin.api.FunLinkPayload
import com.hlju.funlinkbluetooth.core.plugin.api.GamePlugin
import com.hlju.funlinkbluetooth.core.plugin.api.PluginManifest
import com.hlju.funlinkbluetooth.core.plugin.api.TransferStatus
import com.hlju.funlinkbluetooth.core.plugin.api.TransferUpdate
import com.hlju.funlinkbluetooth.core.plugin.api.support.OutgoingTransferManager
import com.hlju.funlinkbluetooth.core.plugin.api.support.PayloadHelper
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.PipedInputStream
import java.io.PipedOutputStream

class IntercomPlugin : GamePlugin(PluginManifest(id = "demo_intercom", name = "实时对讲", requiredQuality = 1)) {

    private val state = IntercomState()
    private val transferManager = OutgoingTransferManager()
    private val incomingPayloads = mutableMapOf<Long, String>()
    private var appContext: Context? = null

    override fun onPayloadReceived(endpointId: String, payload: FunLinkPayload) {
        when (payload) {
            is FunLinkPayload.Stream -> {
                incomingPayloads[payload.id] = "实时对讲流"
                startIncomingStreamPlayback(endpointId, payload)
            }
            else -> Unit
        }
    }

    override fun onPayloadTransferUpdate(endpointId: String, update: TransferUpdate) {
        val payloadId = update.payloadId
        val isTerminal = update.status == TransferStatus.SUCCESS ||
            update.status == TransferStatus.FAILURE ||
            update.status == TransferStatus.CANCELED

        if (isTerminal) {
            val incoming = incomingPayloads.remove(payloadId)
            if (incoming != null) {
                stopIncomingStreamPlayback(payloadId)
            }
        }

        transferManager.handleTransferUpdate(
            endpointId = endpointId,
            update = update,
            appendLog = { appendLog(it) },
            onTerminalStatus = { pid, _ ->
                val tracker = transferManager.outgoingTrackers[pid]
                val wouldBeDone = tracker != null &&
                    tracker.completedEndpoints.size + 1 >= tracker.expectedEndpoints
                if (wouldBeDone && state.outgoingSession?.payloadId == pid) {
                    stopIntercomStreamingInternal(emitLog = false)
                }
            },
            resolveLabel = { incomingPayloads[it] ?: "对讲流" }
        )
    }

    override fun onEndpointDisconnected(endpointId: String) {
        appendLog("--- 端点 $endpointId 已断开 ---")
        stopIntercomStreamingInternal(emitLog = true)
    }

    fun startIntercomStreaming(context: Context) {
        if (state.isStreaming.value) return

        appContext = context.applicationContext

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("--- 录音权限不足，无法开始对讲 ---")
            return
        }

        val endpointIds = hostBindings.connectedEndpointIds
        if (endpointIds.isEmpty()) {
            appendLog("--- 未连接设备，无法开始对讲 ---")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioWorkers.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            appendLog("--- 录音初始化失败：无效缓冲区 ---")
            return
        }

        val recordBufferSize = (minBuffer * 2).coerceAtLeast(AudioWorkers.IO_CHUNK_BYTES)
        val audioRecord = try {
            createAudioRecord(recordBufferSize)
        } catch (_: SecurityException) {
            appendLog("--- 录音权限不足，无法开始对讲 ---")
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            appendLog("--- 录音初始化失败：AudioRecord 未就绪 ---")
            return
        }

        val pipeInput = PipedInputStream(AudioWorkers.STREAM_PIPE_BUFFER_BYTES)
        val pipeOutput = PipedOutputStream(pipeInput)
        val payload = FunLinkPayload.Stream(inputStream = pipeInput)

        val payloadId = PayloadHelper.sendPayloadToConnectedEndpoints(
            hostBindings = hostBindings,
            payload = payload,
            label = "实时对讲流",
            shouldCloseWhenFinished = true,
            outgoingTrackers = transferManager.outgoingTrackers,
            lastOutgoingPayloadId = transferManager.lastOutgoingPayloadId,
            appendLog = { appendLog(it) }
        )
        if (payloadId == null) {
            AudioWorkers.safeStopAndReleaseAudioRecord(audioRecord)
            try { pipeOutput.close() } catch (_: Exception) {}
            return
        }

        val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = AudioWorkers.startCaptureWorker(
            audioRecord = audioRecord,
            outputStream = pipeOutput,
            stopRequested = stopRequested,
            appendLog = { appendLog(it) }
        )
        worker.start()

        state.outgoingSession = OutgoingIntercomSession(
            payloadId = payloadId,
            stopRequested = stopRequested,
            worker = worker,
            audioRecord = audioRecord,
            outputStream = pipeOutput
        )
        state.isStreaming.value = true
        appendLog("--- 实时对讲已开始 (stream id=$payloadId) ---")
    }

    fun stopIntercomStreaming(emitLog: Boolean = true) {
        stopIntercomStreamingInternal(emitLog)
    }

    private fun stopIntercomStreamingInternal(emitLog: Boolean) {
        val session = state.outgoingSession ?: return
        state.outgoingSession = null
        state.isStreaming.value = false

        session.stopRequested.set(true)
        AudioWorkers.safeStopAndReleaseAudioRecord(session.audioRecord)
        try { session.outputStream.close() } catch (_: Exception) {}
        session.worker.interrupt()

        if (emitLog) {
            appendLog("--- 实时对讲已结束 ---")
        }
    }

    private fun startIncomingStreamPlayback(endpointId: String, payload: FunLinkPayload.Stream) {
        val inputStream = payload.inputStream
        if (inputStream == null) {
            appendLog("[$endpointId] 流式载荷读取失败：InputStream 不可用")
            return
        }

        val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = AudioWorkers.startPlaybackWorker(
            inputStream = inputStream,
            payloadId = payload.id,
            stopRequested = stopRequested,
            appendLog = { appendLog(it) },
            onComplete = {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    state.incomingStreamPlaybacks.remove(payload.id)
                }
            }
        )
        worker.start()

        state.incomingStreamPlaybacks[payload.id] = IncomingStreamSession(
            stopRequested = stopRequested,
            worker = worker,
            inputStream = inputStream
        )
        appendLog("[$endpointId] 开始播放实时对讲流 (id=${payload.id})")
    }

    private fun stopIncomingStreamPlayback(payloadId: Long) {
        val session = state.incomingStreamPlaybacks.remove(payloadId) ?: return
        session.stopRequested.set(true)
        try { session.inputStream.close() } catch (_: Exception) {}
        session.worker.interrupt()
    }

    @Composable
    override fun AppIcon(modifier: Modifier) {
        Icon(
            imageVector = MiuixIcons.Ok,
            contentDescription = name,
            modifier = modifier,
            tint = MiuixTheme.colorScheme.primary
        )
    }

    @Composable
    override fun Content() {
        IntercomUi(
            plugin = this,
            state = state,
            onStartClick = { context ->
                startIntercomStreaming(context)
            },
            onStopClick = {
                stopIntercomStreaming(emitLog = true)
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(recordBufferSize: Int): AudioRecord {
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioWorkers.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordBufferSize
        )
    }
}
