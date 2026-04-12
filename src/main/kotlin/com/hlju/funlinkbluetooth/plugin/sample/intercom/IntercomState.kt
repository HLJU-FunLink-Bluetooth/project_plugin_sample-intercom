package com.hlju.funlinkbluetooth.plugin.sample.intercom

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.io.InputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class IncomingStreamSession(
    val stopRequested: AtomicBoolean,
    val worker: Thread,
    val inputStream: InputStream
)

data class OutgoingIntercomSession(
    val payloadId: Long,
    val stopRequested: AtomicBoolean,
    val worker: Thread,
    val audioRecord: android.media.AudioRecord,
    val outputStream: PipedOutputStream
)

class IntercomState {
    val isStreaming: MutableState<Boolean> = mutableStateOf(false)
    val incomingStreamPlaybacks: ConcurrentHashMap<Long, IncomingStreamSession> = ConcurrentHashMap()
    var outgoingSession: OutgoingIntercomSession? = null
}
