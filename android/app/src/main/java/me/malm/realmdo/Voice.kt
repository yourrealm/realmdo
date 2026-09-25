package me.malm.realmdo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * English text to speech for the routine player. Each line cuts off the one
 * before it, and other audio (music at the gym) ducks while it talks.
 */
class Voice(context: Context) : TextToSpeech.OnInitListener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    /** Said once the engine is up; the first line comes before that. */
    private var pending: String? = null
    private var count = 0

    @Volatile
    private var last: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.setLanguage(Locale.US)
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = release(id)
            override fun onStop(id: String?, interrupted: Boolean) = release(id)

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = release(id)
        })
        ready = true
        pending?.let { say(it) }
        pending = null
    }

    /** Only the latest line gives the focus back; an older one was cut off by it. */
    private fun release(id: String?) {
        if (id == last) audio.abandonAudioFocusRequest(focus)
    }

    fun say(text: String) {
        if (!ready) {
            pending = text
            return
        }
        val id = "line-${count++}"
        last = id
        audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        pending = null
        if (ready) tts.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
