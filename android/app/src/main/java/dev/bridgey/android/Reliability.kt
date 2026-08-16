package dev.bridgey.android

import java.io.BufferedReader
import kotlin.math.min

internal const val MAX_PROTOCOL_FRAME_CHARS = 65_536
internal const val MAX_TRANSFER_HISTORY = 20

internal class ProtocolFrameTooLargeException : IllegalArgumentException("Protocol frame is too large")

internal fun BufferedReader.readProtocolLine(maxChars: Int = MAX_PROTOCOL_FRAME_CHARS): String? {
    val value = StringBuilder(min(maxChars, 1024))
    while (true) {
        when (val character = read()) {
            -1 -> return value.takeIf { it.isNotEmpty() }?.toString()
            '\n'.code -> return value.toString()
            '\r'.code -> Unit
            else -> {
                if (value.length >= maxChars) throw ProtocolFrameTooLargeException()
                value.append(character.toChar())
            }
        }
    }
}

internal fun reconnectDelayMillis(attempt: Int): Long {
    val boundedAttempt = attempt.coerceIn(0, 30)
    return min(1L shl boundedAttempt, 30L) * 1_000L
}

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
