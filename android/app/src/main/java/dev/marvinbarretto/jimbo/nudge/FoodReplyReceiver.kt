package dev.marvinbarretto.jimbo.nudge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.marvinbarretto.jimbo.JimboClient
import kotlin.concurrent.thread

/** Receives the nudge's inline reply (or the retry tap) and logs it without opening the app. */
class FoodReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NudgeNotifier.KEY_REPLY)?.toString()
            ?: intent.getStringExtra(NudgeNotifier.EXTRA_RETRY_TEXT)
        if (text.isNullOrBlank()) return

        val pending = goAsync()
        val app = context.applicationContext
        thread {
            try {
                when (FoodReplyHandler(JimboClient).handle(text.trim())) {
                    ReplyOutcome.Logged -> {
                        NudgeNotifier.cancelNudge(app)
                        NudgeNotifier.cancelRetry(app)
                    }
                    is ReplyOutcome.RetryNeeded -> {
                        NudgeNotifier.cancelNudge(app)
                        NudgeNotifier.postRetry(app, text.trim())
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
