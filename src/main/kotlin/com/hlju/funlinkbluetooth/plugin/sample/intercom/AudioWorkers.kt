package com.hlju.funlinkbluetooth.plugin.sample.intercom

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

object AudioWorkers {

    const val SAMPLE_RATE = 16_000
    const val STREAM_PIPE_BUFFER_BYTES = 128 * 1024
    const val IO_CHUNK_BYTES = 2048

    fun startCaptureWorker(
        audioRecord: AudioRecord,
        outputStream: java.io.PipedOutputStream,
        stopRequested: AtomicBoolean,
        appendLog: (String) -> Unit
    ): Thread {
        val buffer = ByteArray(audioRecord.bufferSizeInFrames * 2)
        val worker = Thread {
            try {
                audioRecord.startRecording()
                while (!stopRequested.get()) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                        outputStream.flush()
                    } else if (read == AudioRecord.ERROR_BAD_VALUE ||
                        read == AudioRecord.ERROR_INVALID_OPERATION
                    ) {
                        appendLog("--- 对讲采集失败：AudioRecord.read=$read ---")
                        break
                    }
                }
            } catch (_: SecurityException) {
                if (!stopRequested.get()) {
                    appendLog("--- 录音权限不足，已停止对讲 ---")
                }
            } catch (exception: Exception) {
                if (!stopRequested.get()) {
                    appendLog("--- 对讲采集异常：${exception.localizedMessage ?: "未知错误"} ---")
                }
            } finally {
                try {
                    outputStream.close()
                } catch (_: Exception) {
                }
                safeStopAndReleaseAudioRecord(audioRecord)
            }
        }
        worker.name = "FunLink-Intercom-Capture"
        worker.isDaemon = true
        return worker
    }

    fun startPlaybackWorker(
        inputStream: InputStream,
        payloadId: Long,
        stopRequested: AtomicBoolean,
        appendLog: (String) -> Unit,
        onComplete: () -> Unit
    ): Thread {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(IO_CHUNK_BYTES))
            .build()

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            audioTrack.release()
            appendLog("流播放初始化失败：AudioTrack 未就绪")
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
            return Thread {}
        }

        val worker = Thread {
            val buffer = ByteArray(IO_CHUNK_BYTES)
            try {
                audioTrack.play()
                while (!stopRequested.get()) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break
                    var written = 0
                    while (written < read && !stopRequested.get()) {
                        val size = audioTrack.write(
                            buffer,
                            written,
                            read - written,
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (size <= 0) break
                        written += size
                    }
                }
            } catch (exception: Exception) {
                if (!stopRequested.get()) {
                    appendLog("对讲播放异常：${exception.localizedMessage ?: "未知错误"}")
                }
            } finally {
                try {
                    inputStream.close()
                } catch (_: Exception) {
                }
                try {
                    audioTrack.stop()
                } catch (_: Exception) {
                }
                audioTrack.release()
                onComplete()
            }
        }
        worker.name = "FunLink-Intercom-Playback-$payloadId"
        worker.isDaemon = true
        return worker
    }

    fun safeStopAndReleaseAudioRecord(audioRecord: AudioRecord) {
        try {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        } catch (_: Exception) {
        }
        audioRecord.release()
    }
}
