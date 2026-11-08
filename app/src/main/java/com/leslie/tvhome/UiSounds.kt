package com.leslie.tvhome

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File

/**
 * Optional tvOS-like interface sounds. The files are never shipped with the app: the owner puts
 * navigate.wav, click.wav and back.wav into Android/data/com.leslie.tvhome/files/sounds/.
 * A missing file means that sound stays silent.
 */
object UiSounds {
    enum class Kind(val file: String) { NAVIGATE("navigate.wav"), CLICK("click.wav"), BACK("back.wav") }

    /** Minimum gap between navigate sounds, and every other key repeat is skipped. */
    class Gate(private val gapMs: Long = 45) {
        private var last = Long.MIN_VALUE / 2
        fun allow(now: Long, repeatCount: Int): Boolean {
            if (repeatCount % 2 == 1 || now - last < gapMs) return false
            last = now
            return true
        }
    }

    @Volatile private var pool: SoundPool? = null
    @Volatile private var ids: Map<Kind, Int> = emptyMap()
    /** Mirrors Prefs.uiSounds so the key path never touches SharedPreferences. */
    @Volatile var enabled = false
    private val gate = Gate()

    fun dir(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, "sounds") }

    fun available(ctx: Context): Boolean = dir(ctx)?.let { d -> Kind.values().any { File(d, it.file).isFile } } == true

    /** Main thread only (key dispatch). */
    fun play(ctx: Context, kind: Kind, repeatCount: Int = 0) {
        if (!enabled) return
        if (kind == Kind.NAVIGATE && !gate.allow(SystemClock.uptimeMillis(), repeatCount)) return
        val p = pool ?: return
        ids[kind]?.let { p.play(it, 1f, 1f, 1, 0, 1f) }
    }

    /** (Re)loads the files off the main thread; call on activity create so newly added files are picked up. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val on = Prefs(app).uiSounds
            val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            val d = dir(app)
            val m = HashMap<Kind, Int>()
            for (k in Kind.values()) {
                val f = d?.let { File(it, k.file) }
                if (f != null && f.isFile) runCatching { m[k] = p.load(f.path, 1) }
            }
            val old = pool
            ids = m; pool = p; enabled = on
            old?.release()
        }.start()
    }

    /** Runs [dispatch] and plays navigate when a D-pad press moved focus, click when OK was consumed. */
    inline fun around(act: Activity, e: KeyEvent, dispatch: () -> Boolean): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return dispatch()
        val before = act.currentFocus
        val handled = dispatch()
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (act.currentFocus !== before) play(act, Kind.NAVIGATE, e.repeatCount)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (handled && e.repeatCount == 0) play(act, Kind.CLICK)
        }
        return handled
    }
}
