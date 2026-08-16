package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BridgeyDiagnosticsTest {
    @Test fun diagnosticsAreBoundedAndStoreOnlyNormalizedEventMetadata() {
        val diagnostics = BridgeyDiagnostics(limit = 2)
        diagnostics.record("Transport", "Connected to Semyon's Mac", "OK")
        diagnostics.record("Plugin", "Clipboard Content", "Rejected")
        diagnostics.record("Transfer", "Interrupted", "Needs Retry")

        val events = diagnostics.snapshot()

        assertEquals(2, events.size)
        assertEquals("transfer", events.last().category)
        assertEquals("needs_retry", events.last().outcome)
        assertFalse(events.any { it.event.contains("Semyon", ignoreCase = true) })
    }
}
