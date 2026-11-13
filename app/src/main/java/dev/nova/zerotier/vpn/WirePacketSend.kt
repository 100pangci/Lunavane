package dev.nova.zerotier.vpn

import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.channels.ClosedChannelException

internal enum class WirePacketSocketState {
    READY,
    MISSING,
    CLOSED,
    REPLACED,
}

internal enum class WirePacketFailureCategory {
    NETWORK_UNREACHABLE,
    INVALID_DESTINATION,
    SOCKET_CLOSED,
    SOCKET_BACKPRESSURE,
    SOCKET_ERROR,
    IO_ERROR,
    CALLBACK_ERROR,
}

internal sealed interface WirePacketSendResult {
    data object Sent : WirePacketSendResult
    data object StaleSession : WirePacketSendResult
    data object Stopping : WirePacketSendResult
    data object SocketUnavailable : WirePacketSendResult
    data object SocketClosed : WirePacketSendResult
    data object SocketReplaced : WirePacketSendResult
    data class Failed(val category: WirePacketFailureCategory, val message: String) : WirePacketSendResult
}

internal fun attemptWirePacketSend(
    sessionIsCurrent: () -> Boolean,
    stopping: () -> Boolean,
    socketState: () -> WirePacketSocketState,
    send: () -> Boolean,
): WirePacketSendResult {
    if (!sessionIsCurrent()) return WirePacketSendResult.StaleSession
    if (stopping()) return WirePacketSendResult.Stopping
    socketState().failureOrNull()?.let { return it }

    if (!sessionIsCurrent()) return WirePacketSendResult.StaleSession
    if (stopping()) return WirePacketSendResult.Stopping
    socketState().failureOrNull()?.let { return it }

    return try {
        if (send()) {
            WirePacketSendResult.Sent
        } else {
            WirePacketSendResult.Failed(
                WirePacketFailureCategory.SOCKET_BACKPRESSURE,
                "Non-blocking UDP send did not accept the datagram",
            )
        }
    } catch (exception: IOException) {
        WirePacketSendResult.Failed(exception.failureCategory(), exception.messageOrType())
    } catch (exception: Exception) {
        WirePacketSendResult.Failed(WirePacketFailureCategory.CALLBACK_ERROR, exception.messageOrType())
    }
}

internal fun shouldFallbackToDefaultRoute(
    category: WirePacketFailureCategory,
    socketBoundToNetwork: Boolean,
): Boolean = category == WirePacketFailureCategory.NETWORK_UNREACHABLE && socketBoundToNetwork

internal fun shouldRetryNetworkBinding(
    socketBoundToNetwork: Boolean,
    lastBindFallbackNanos: Long,
    nowNanos: Long,
    cooldownNanos: Long,
): Boolean = !socketBoundToNetwork &&
    (lastBindFallbackNanos == 0L || nowNanos - lastBindFallbackNanos >= cooldownNanos)

private fun WirePacketSocketState.failureOrNull(): WirePacketSendResult? = when (this) {
    WirePacketSocketState.READY -> null
    WirePacketSocketState.MISSING -> WirePacketSendResult.SocketUnavailable
    WirePacketSocketState.CLOSED -> WirePacketSendResult.SocketClosed
    WirePacketSocketState.REPLACED -> WirePacketSendResult.SocketReplaced
}

private fun IOException.failureCategory(): WirePacketFailureCategory = when {
    this is ClosedChannelException || this is SocketException && message.orEmpty().contains("socket closed", ignoreCase = true) ->
        WirePacketFailureCategory.SOCKET_CLOSED
    this is UnknownHostException -> WirePacketFailureCategory.INVALID_DESTINATION
    this is SocketException && isUnderlyingNetworkUnavailable() -> WirePacketFailureCategory.NETWORK_UNREACHABLE
    this is SocketException -> WirePacketFailureCategory.SOCKET_ERROR
    else -> WirePacketFailureCategory.IO_ERROR
}

private fun SocketException.isUnderlyingNetworkUnavailable(): Boolean {
    val message = message?.lowercase().orEmpty()
    return message.contains("network is unreachable") ||
        message.contains("network unreachable") ||
        message.contains("no route to host") ||
        message.contains("network is down")
}

private fun Exception.messageOrType(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
