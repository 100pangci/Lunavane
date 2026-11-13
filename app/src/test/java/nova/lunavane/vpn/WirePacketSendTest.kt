package nova.lunavane.vpn

import java.io.IOException
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirePacketSendTest {
    @Test
    fun staleSessionDoesNotSend() {
        var sent = false

        val result = attemptWirePacketSend(
            sessionIsCurrent = { false },
            stopping = { false },
            socketState = { WirePacketSocketState.READY },
            send = { sent = true; true },
        )

        assertEquals(WirePacketSendResult.StaleSession, result)
        assertFalse(sent)
    }

    @Test
    fun stoppingSessionDoesNotSend() {
        var sent = false

        val result = attemptWirePacketSend(
            sessionIsCurrent = { true },
            stopping = { true },
            socketState = { WirePacketSocketState.READY },
            send = { sent = true; true },
        )

        assertEquals(WirePacketSendResult.Stopping, result)
        assertFalse(sent)
    }

    @Test
    fun unavailableOrClosedSocketDoesNotSend() {
        var sent = false
        val missing = attemptWirePacketSend(
            sessionIsCurrent = { true },
            stopping = { false },
            socketState = { WirePacketSocketState.MISSING },
            send = { sent = true; true },
        )
        val closed = attemptWirePacketSend(
            sessionIsCurrent = { true },
            stopping = { false },
            socketState = { WirePacketSocketState.CLOSED },
            send = { sent = true; true },
        )

        assertEquals(WirePacketSendResult.SocketUnavailable, missing)
        assertEquals(WirePacketSendResult.SocketClosed, closed)
        assertFalse(sent)
    }

    @Test
    fun socketReplacementDuringPreflightDoesNotSend() {
        var sent = false
        var checks = 0

        val result = attemptWirePacketSend(
            sessionIsCurrent = { true },
            stopping = { false },
            socketState = {
                checks++
                if (checks == 1) WirePacketSocketState.READY else WirePacketSocketState.REPLACED
            },
            send = { sent = true; true },
        )

        assertEquals(WirePacketSendResult.SocketReplaced, result)
        assertFalse(sent)
    }

    @Test
    fun unacceptedNonblockingDatagramIsReportedAsBackpressure() {
        val result = attemptWirePacketSend(
            sessionIsCurrent = { true },
            stopping = { false },
            socketState = { WirePacketSocketState.READY },
            send = { false },
        )

        assertEquals(
            WirePacketSendResult.Failed(
                WirePacketFailureCategory.SOCKET_BACKPRESSURE,
                "Non-blocking UDP send did not accept the datagram",
            ),
            result,
        )
    }

    @Test
    fun sendExceptionIsReturnedAsFailure() {
        val result = attemptWirePacketSend(
            sessionIsCurrent = { true },
            stopping = { false },
            socketState = { WirePacketSocketState.READY },
            send = { throw SocketException("Network is unreachable") },
        )

        assertEquals(
            WirePacketSendResult.Failed(
                WirePacketFailureCategory.NETWORK_UNREACHABLE,
                "Network is unreachable",
            ),
            result,
        )
        assertFalse(result is WirePacketSendResult.Sent)
    }

    @Test
    fun successfulSendIsReportedOnlyAfterSenderReturns() {
        val result = attemptWirePacketSend(
            sessionIsCurrent = { true },
            stopping = { false },
            socketState = { WirePacketSocketState.READY },
            send = { true },
        )

        assertTrue(result is WirePacketSendResult.Sent)
    }

    @Test
    fun otherIoFailuresKeepTheirCauseCategory() {
        val result = attemptWirePacketSend(
            sessionIsCurrent = { true },
            stopping = { false },
            socketState = { WirePacketSocketState.READY },
            send = { throw IOException("write failed") },
        )

        assertEquals(
            WirePacketSendResult.Failed(WirePacketFailureCategory.IO_ERROR, "write failed"),
            result,
        )
    }

    @Test
    fun networkUnreachableFallsBackToDefaultRouteOnlyForNetworkBoundSocket() {
        assertTrue(shouldFallbackToDefaultRoute(WirePacketFailureCategory.NETWORK_UNREACHABLE, true))
        assertFalse(shouldFallbackToDefaultRoute(WirePacketFailureCategory.NETWORK_UNREACHABLE, false))
        assertFalse(shouldFallbackToDefaultRoute(WirePacketFailureCategory.SOCKET_ERROR, true))
    }

    @Test
    fun networkBindingIsNotRetriedWhileDefaultRouteCooldownIsActive() {
        val cooldown = 5_000_000_000L
        val fallbackAt = 100_000_000_000L

        assertTrue(shouldRetryNetworkBinding(false, 0L, fallbackAt, cooldown))
        assertFalse(shouldRetryNetworkBinding(false, fallbackAt, fallbackAt + 1_000_000_000L, cooldown))
        assertTrue(shouldRetryNetworkBinding(false, fallbackAt, fallbackAt + cooldown, cooldown))
        assertFalse(shouldRetryNetworkBinding(true, fallbackAt, fallbackAt + cooldown, cooldown))
    }
}
