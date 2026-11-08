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

/**
 * WalkAudioFocus on AudioManager. A permanent loss goes to onLoss; a transient
 * loss, a duck and focus coming back go to onChange (WalkAudio pauses, plays
 * on, resumes). The request is kept through a transient loss: the system
 * hands focus back to the same request.
 */
class AndroidWalkAudioFocus(context: Context) : WalkAudioFocus {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var held: AudioFocusRequest? = null

    /** Without change reporting, a transient loss counts as a loss (walk 1's behaviour). */
    override fun request(gain: Int, onLoss: () -> Unit): Boolean =
        requestWithChanges(gain, onLoss) { if (it == FocusChange.LOSS_TRANSIENT) onLoss() }

    override fun requestWithChanges(gain: Int, onLoss: () -> Unit, onChange: (FocusChange) -> Unit): Boolean {
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(SPEECH)
            .setOnAudioFocusChangeListener({ change ->
                Log.d(TAG, "Audio focus change $change")
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> onLoss()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onChange(FocusChange.LOSS_TRANSIENT)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onChange(FocusChange.DUCK)
                    AudioManager.AUDIOFOCUS_GAIN -> onChange(FocusChange.GAIN)
                }
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
 * A file can pause, resume and seek; a placeholder cannot (pause() is false).
 * Callbacks arrive on the main looper.
 */
class AndroidWalkClipPlayer(private val context: Context) : WalkClipPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    // A pause that arrives while the file is still preparing holds its start until resume.
    private var prepared = false
    private var holdStart = false
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
        prepared = false
        holdStart = false
        tts?.stop()
    }

    override fun pause(): Boolean {
        val player = media ?: return false
        if (!prepared) { holdStart = true; return true }
        return runCatching { if (player.isPlaying) player.pause() }.isSuccess
    }

    override fun resume() {
        val player = media ?: return
        if (!prepared) { holdStart = false; return }
        runCatching { player.start() }.onFailure { Log.e(TAG, "Clip would not resume", it) }
    }

    override fun seekBy(ms: Int) {
        val player = media?.takeIf { prepared } ?: return
        runCatching { player.seekTo(maxOf(0, player.currentPosition + ms)) }
    }

    override fun positionMs(): Int? = media?.takeIf { prepared }?.let { runCatching { it.currentPosition }.getOrNull() }

    override fun hasClip(clip: String): Boolean = clipFile(clip) != null

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
        prepared = false
        holdStart = false
        try {
            player.setAudioAttributes(SPEECH)
            player.setDataSource(file.path)
            player.setOnPreparedListener {
                if (media !== it) return@setOnPreparedListener
                prepared = true
                if (!holdStart) it.start()
            }
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
        if (media === player) {
            media = null
            prepared = false
            holdStart = false
        }
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
