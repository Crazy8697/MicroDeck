package net.darkrelay.microdeck

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Drives view_update_bar: shows Updates.pending when it should be offered. Download and Release
 * notes open the browser (no install permission; Android installs from the downloaded file), Skip
 * remembers the version, Later hides the bar until the next launch.
 */
class UpdateBar(private val activity: Activity, private val root: View) {

    private val text: TextView = root.findViewById(R.id.update_text)
    private var shown: UpdateCheck.Release? = null

    init {
        root.findViewById<Button>(R.id.update_download).setOnClickListener { shown?.let { open(it.apkUrl) } }
        root.findViewById<Button>(R.id.update_notes).setOnClickListener { shown?.let { open(it.notesUrl) } }
        root.findViewById<Button>(R.id.update_skip).setOnClickListener {
            shown?.let { Updates.skip(activity, it.version) }
            hide()
        }
        root.findViewById<Button>(R.id.update_later).setOnClickListener {
            Updates.laterThisLaunch = true
            hide()
        }
    }

    /** Show or hide to match the pending release, the skip and Later. */
    fun refresh() {
        val r = Updates.pending
        if (r != null && Updates.shouldShowPending(activity)) show(r) else hide()
    }

    fun show(r: UpdateCheck.Release) {
        shown = r
        text.text = activity.getString(R.string.update_available, r.version)
        root.visibility = View.VISIBLE
    }

    fun hide() {
        root.visibility = View.GONE
    }

    private fun open(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Log.w("MicroDeck", "no browser for the update link", e)
            Toast.makeText(activity, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
