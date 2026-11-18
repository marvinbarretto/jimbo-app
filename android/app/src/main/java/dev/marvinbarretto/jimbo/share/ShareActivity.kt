package dev.marvinbarretto.jimbo.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/** Share target: no UI. Queues the send and finishes at once. Never opens the WebView. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = if (intent?.action == Intent.ACTION_SEND) {
            ShareLink.extract(
                intent.getStringExtra(Intent.EXTRA_TEXT),
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
            )
        } else null
        if (item == null) {
            Log.w(SHARE_TAG, "nothing to share")
            Toast.makeText(this, "Nothing to send to Jimbo", Toast.LENGTH_SHORT).show()
        } else {
            ShareWorker.enqueue(applicationContext, item)
            Toast.makeText(this, "Sent to Jimbo", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
