package dev.marvinbarretto.jimbo.walk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File

private const val TAG = "JimboWalk"

private val SPEECH: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/** WalkAudioFocus on AudioManager. A ducking request is not a loss: the clip plays on. */
class AndroidWalkAudioFocus(context: Context) : WalkAudioFocus {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var held: AudioFocusRequest? = null

    override fun request(gain: Int, onLoss: () -> Unit): Boolean {
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(SPEECH)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLoss()
            }, main)
            .build()
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Audio focus request (gain $gain): ${if (granted) "granted" else "refused"}")
        held = if (granted) request else null
        return granted
    }

    override fun abandon() {
        held?.let { manager.abandonAudioFocusRequest(it) }
        held = null
    }
}

/**
 * Plays a clip by name: a file of that name, or that name plus any extension,
 * in the app's external files dir under `walk/`
 * (/sdcard/Android/data/dev.marvinbarretto.jimbo/files/walk/, `adb push` puts
 * them there). No such file means a placeholder: the clip name spoken aloud by
 * TextToSpeech, a few seconds long so the podcast pause is noticeable.
 * Callbacks arrive on the main looper.
 */
class AndroidWalkClipPlayer(private val context: Context) : WalkClipPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    private var ttsReady = false
    private var pendingSpeech: (() -> Unit)? = null
    private var tts: TextToSpeech? = TextToSpeech(context) { status ->
        main.post {
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) {
                // No engine: drop it, so every placeholder fails at once and hands focus back.
                Log.e(TAG, "TextToSpeech unavailable (status $status)")
                tts?.shutdown()
                tts = null
            }
            pendingSpeech?.invoke()
            pendingSpeech = null
        }
    }

    override fun play(clip: String, onDone: () -> Unit, onError: () -> Unit) {
        val file = clipFile(clip)
        if (file != null) playFile(file, onDone, onError) else speakPlaceholder(clip, onDone, onError)
    }

    override fun stop() {
        pendingSpeech = null
        media?.let { runCatching { it.stop() }; it.release() }
        media = null
        tts?.stop()
    }

    /** Ends the session's audio for good: the service calls this on stop. */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun clipFile(clip: String): File? {
        val dir = context.getExternalFilesDir("walk") ?: return null
        File(dir, clip).takeIf { it.isFile }?.let { return it }
        return dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == clip }
    }

    private fun playFile(file: File, onDone: () -> Unit, onError: () -> Unit) {
        val player = MediaPlayer()
        media = player
        try {
            player.setAudioAttributes(SPEECH)
            player.setDataSource(file.path)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { release(it); onDone() }
            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "Clip ${file.name} failed ($what/$extra)")
                release(mp)
                onError()
                true
            }
            player.prepareAsync()
            Log.d(TAG, "Playing clip ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Clip ${file.name} would not start", e)
            release(player)
            main.post(onError)
        }
    }

    private fun release(player: MediaPlayer) {
        player.release()
        if (media === player) media = null
    }

    private fun speakPlaceholder(clip: String, onDone: () -> Unit, onError: () -> Unit) {
        val speak = speak@{
            val engine = tts
            if (!ttsReady || engine == null) {
                onError()
                return@speak
            }
            val utterance = "walk-$clip-${System.nanoTime()}"
            engine.setAudioAttributes(SPEECH)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { if (id == utterance) main.post(onDone) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { if (id == utterance) main.post(onError) }
                override fun onError(id: String?, code: Int) { if (id == utterance) main.post(onError) }
            })
            val text = "Placeholder story for $clip. The story for this spot will play here. " +
                "Your podcast comes back when it ends."
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance) != TextToSpeech.SUCCESS) onError()
            Log.d(TAG, "Speaking placeholder for $clip")
        }
        if (ttsReady || tts == null) speak() else pendingSpeech = speak
    }
}
