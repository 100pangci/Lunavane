package nova.lunavane

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class DiagnosticEntry(
    val id: Long,
    val timestampMillis: Long,
    val message: String,
)

internal object DiagnosticLog {
    private const val MAX_ENTRIES = 100
    private val nextId = AtomicLong(0)
    private val mutableEntries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    fun append(message: String) {
        if (message.isBlank()) return
        val entry = DiagnosticEntry(nextId.incrementAndGet(), System.currentTimeMillis(), message)
        mutableEntries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    fun clear() {
        mutableEntries.value = emptyList()
    }
}
