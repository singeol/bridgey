package dev.bridgey.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.min

internal const val MAX_PROTOCOL_FRAME_BYTES = 65_536
internal const val MAX_TRANSFER_HISTORY = 20

internal class ProtocolFrameTooLargeException : IllegalArgumentException("Protocol frame is too large")

/**
 * Reads one frame: the bytes up to LF, without one CR right before the LF, as the Mac reads frames.
 * Over [maxBytes] throws [ProtocolFrameTooLargeException]; invalid UTF-8 throws a CharacterCodingException.
 */
internal fun InputStream.readProtocolLine(maxBytes: Int = MAX_PROTOCOL_FRAME_BYTES): String? {
    val value = ByteArrayOutputStream(min(maxBytes, 1024))
    while (true) {
        when (val byte = read()) {
            -1 -> return value.takeIf { it.size() > 0 }?.toFrameString(maxBytes)
            '\n'.code -> return value.toFrameString(maxBytes)
            else -> {
                // One byte over the limit is allowed only for the CR that may end the frame.
                if (value.size() > maxBytes || (value.size() == maxBytes && byte != '\r'.code)) throw ProtocolFrameTooLargeException()
                value.write(byte)
            }
        }
    }
}

private fun ByteArrayOutputStream.toFrameString(maxBytes: Int): String {
    val bytes = toByteArray()
    val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
    if (length > maxBytes) throw ProtocolFrameTooLargeException()
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, 0, length))
        .toString()
}

internal fun reconnectDelayMillis(attempt: Int): Long {
    val boundedAttempt = attempt.coerceIn(0, 30)
    return min(1L shl boundedAttempt, 30L) * 1_000L
}

internal fun heartbeatExpired(
    supported: Boolean,
    lastReceivedAtMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long = 30_000L,
): Boolean = supported && nowMillis - lastReceivedAtMillis >= timeoutMillis

internal fun recoverInterruptedTransfers(
    transfers: Map<String, FileTransferState>,
): Map<String, FileTransferState> = transfers
    .mapValues { (_, transfer) ->
        if (transfer.active) transfer.copy(
            status = "Transfer interrupted — reconnect to retry",
            active = false,
            progressPercent = null,
        ) else transfer
    }
    .values
    .sortedByDescending(FileTransferState::startedAtMillis)
    .take(MAX_TRANSFER_HISTORY)
    .associateBy(FileTransferState::id)
