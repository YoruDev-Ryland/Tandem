package dev.tandem.app.ui.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.View
import dev.tandem.app.R

/** Settings › Appearance's two switches, read at every cue. */
data class CueSettings(val haptics: Boolean, val sounds: Boolean)

/** The UI sound for a cue: move, open (A and the secondary actions) and back. A reject is felt, not heard. */
fun soundFor(cue: Cue): Int? = when (cue) {
    Cue.Move -> R.raw.ui_move
    Cue.Confirm -> R.raw.ui_open
    Cue.Back -> R.raw.ui_back
    Cue.Reject -> null
}

/** How loud the UI sounds play, on top of their own quiet level (about -14 dBFS) and the device's volume. */
private const val SOUND_VOLUME = 0.6f

/**
 * Plays cues on [view]: haptics through performHapticFeedback (so the system's touch-feedback setting is respected),
 * and, when switched on, the UI sounds through a SoundPool on the sonification stream. The pool exists only while
 * sounds are on ([prepare]); [release] frees it.
 */
class AndroidCuePlayer(
    private val context: Context,
    private val view: () -> View?,
    private val settings: () -> CueSettings,
) : CuePlayer {
    private var pool: SoundPool? = null
    private val ids = HashMap<Int, Int>()
    private val ready = HashSet<Int>()

    override fun play(cue: Cue) {
        val s = settings()
        if (s.haptics) view()?.performHapticFeedback(hapticFor(cue))
        if (s.sounds) {
            prepare(true)
            val res = soundFor(cue) ?: return
            val id = ids[res] ?: return
            if (id in ready) pool?.play(id, SOUND_VOLUME, SOUND_VOLUME, 1, 0, 1f)
        }
    }

    /** Loads the sounds when [sounds] is on (the first press after switching on may come before they are ready). */
    fun prepare(sounds: Boolean) {
        if (!sounds) { release(); return }
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready += id }
        for (res in listOf(R.raw.ui_move, R.raw.ui_open, R.raw.ui_back)) ids[res] = p.load(context, res, 1)
        pool = p
    }

    fun release() {
        pool?.release()
        pool = null
        ids.clear()
        ready.clear()
    }
}
