package io.freemytv

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Soft click for the home screen (moving focus and opening). The app synthesizes the sound itself (a short, low
 * "tock"), writes it once as a WAV in the cache and plays it quietly through SoundPool. It only plays on the home
 * screen, never inside apps.
 */
internal class Click(c: Context) {
    private var pool: SoundPool? = null
    private var moveSound = 0
    private var openSound = 0

    init {
        try {
            val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            pool = p
            moveSound = p.load(wav(c, "click-move.wav", 1450f, 0.009f, 0.030f), 1)
            openSound = p.load(wav(c, "click-open.wav", 880f, 0.022f, 0.090f), 1)
        } catch (e: Exception) {   // IOException or RuntimeException
            AppLog.w("click sound unavailable: " + e.message)
            release()
        }
    }

    fun move() {
        if (Prefs.sounds()) pool?.play(moveSound, 0.14f, 0.14f, 1, 0, 1f)
    }

    fun open() {
        if (Prefs.sounds()) pool?.play(openSound, 0.18f, 0.18f, 1, 0, 1f)
    }

    fun release() {
        pool?.release()
        pool = null
    }

    private companion object {
        const val RATE = 48_000

        /** Sine wave plus one harmonic, 1.5 ms attack (no pop) and exponential decay. */
        fun wav(c: Context, name: String, freq: Float, decay: Float, seconds: Float): String {
            val f = File(c.cacheDir, name)
            if (f.exists() && f.length() > 44) return f.path
            val n = (RATE * seconds).roundToInt()
            val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + n * 2).put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
                .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            b.put("data".toByteArray(Charsets.US_ASCII)).putInt(n * 2)
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val env = min(1.0, t / 0.0015) * exp(-t / decay)
                val s = 0.75 * sin(2 * PI * freq * t) + 0.25 * sin(2 * PI * freq * 2.01 * t)
                b.putShort((s * env * 0.7 * Short.MAX_VALUE).roundToInt().toShort())
            }
            f.writeBytes(b.array())
            return f.path
        }
    }
}
