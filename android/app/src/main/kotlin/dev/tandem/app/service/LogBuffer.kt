package dev.tandem.app.service

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A bounded ring of timestamped log lines (oldest dropped first). Thread-safe. [lines] holds the newest [window]
 * lines, oldest first, updated on every [add].
 */
class LogRing(
    private val capacity: Int,
    private val window: Int = capacity,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val ring = ArrayDeque<String>(capacity)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private val recent = MutableStateFlow<List<String>>(emptyList())

    val lines: StateFlow<List<String>> get() = recent

    @Synchronized
    fun add(line: String) {
        if (ring.size == capacity) ring.removeFirst()
        ring.addLast("${fmt.format(Date(clock()))} $line")
        recent.value = ring.toList().takeLast(window)
    }

    /** The newest [n] lines, oldest first. */
    @Synchronized
    fun last(n: Int): List<String> = ring.toList().takeLast(n)

    @Synchronized
    fun size(): Int = ring.size
}

/** The process-wide log: an in-memory ring of 500 lines, mirrored to logcat. [lines] is the newest 200. */
object LogBuffer {
    private const val TAG = "Tandem"
    private val ring = LogRing(500, window = 200)

    val lines: StateFlow<List<String>> get() = ring.lines

    fun add(line: String) {
        ring.add(line)
        Log.i(TAG, line)
    }

    fun last(n: Int): List<String> = ring.last(n)
}
