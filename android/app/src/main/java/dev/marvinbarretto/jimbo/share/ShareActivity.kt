package dev.marvinbarretto.jimbo.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Share target: a small dialog-style sheet over the sharing app (plain views, no new
 * dependency; never the WebView). Shows the link, an optional "why" note and a
 * "Research this" box. Send queues the worker (ShareLink.withNote shapes the body);
 * Cancel, back or an outside tap queues nothing.
 */
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
            finish()
            return
        }

        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )

        val pad = dp(20)
        val note = EditText(this).apply {
            hint = "Why it caught your eye (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
        }
        val research = CheckBox(this).apply { text = "Research this" }
        val link = TextView(this).apply {
            text = item.title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 14f
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener {
                ShareWorker.enqueue(
                    applicationContext,
                    ShareLink.withNote(item, note.text?.toString(), research.isChecked),
                )
                // Queued, not sent: the worker sends when there is a network and notifies if it fails.
                Toast.makeText(this@ShareActivity, "Queued for Jimbo", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancel)
            addView(send)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(8))
            addView(link)
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(research)
            addView(buttons)
        }
        setContentView(root)
        note.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
