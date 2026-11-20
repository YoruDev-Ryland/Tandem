package dev.emusync.app.service

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A bounded ring of timestamped log lines (oldest dropped first). Thread-safe. */
class LogRing(private val capacity: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val lines = ArrayDeque<String>(capacity)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast("${fmt.format(Date(clock()))} $line")
    }

    /** The newest [n] lines, oldest first. */
    @Synchronized
    fun last(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun size(): Int = lines.size
}

/** The process-wide log: an in-memory ring of 500 lines, mirrored to logcat. */
object LogBuffer {
    private const val TAG = "EmuSync"
    private val ring = LogRing(500)

    fun add(line: String) {
        ring.add(line)
        Log.i(TAG, line)
    }

    fun last(n: Int): List<String> = ring.last(n)
}
