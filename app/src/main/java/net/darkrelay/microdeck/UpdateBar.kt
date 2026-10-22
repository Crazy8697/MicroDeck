package net.darkrelay.microdeck

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Drives view_update_bar. The offer (Updates.pending, unless skipped or put off with Later) has Download
 * and install, Release notes, Skip this version and Later. After that it shows UpdateInstaller's state:
 * progress with Cancel, the one-time install permission with Allow, and failures with Retry. Open in
 * browser is the old way (the APK in the default browser) for when the in-app install isn't wanted or
 * doesn't work. The host activity calls onResume / onPause.
 */
class UpdateBar(private val activity: Activity, private val root: View) {

    private val text: TextView = root.findViewById(R.id.update_text)
    private val detail: TextView = root.findViewById(R.id.update_detail)
    private val progress: ProgressBar = root.findViewById(R.id.update_progress)
    private val download: Button = root.findViewById(R.id.update_download)
    private val cancel: Button = root.findViewById(R.id.update_cancel)
    private val allow: Button = root.findViewById(R.id.update_allow)
    private val retry: Button = root.findViewById(R.id.update_retry)
    private val browser: Button = root.findViewById(R.id.update_browser)
    private val notes: Button = root.findViewById(R.id.update_notes)
    private val skip: Button = root.findViewById(R.id.update_skip)
    private val later: Button = root.findViewById(R.id.update_later)
    private var shown: UpdateCheck.Release? = null
    private val listener: () -> Unit = { if (!activity.isFinishing && !activity.isDestroyed) refresh() }

    init {
        download.setOnClickListener { shown?.let { UpdateInstaller.start(activity, it) }; refresh() }
        cancel.setOnClickListener { UpdateInstaller.cancel() }
        allow.setOnClickListener { UpdateInstaller.openPermissionSettings(activity) }
        retry.setOnClickListener {
            val r = UpdateInstaller.release ?: shown
            UpdateInstaller.reset(activity)
            if (r != null) UpdateInstaller.start(activity, r)
            refresh()
        }
        browser.setOnClickListener {
            val url = (UpdateInstaller.release ?: shown)?.apkUrl ?: LATEST_APK
            open(Browsers.viewIntent(activity, url))
        }
        notes.setOnClickListener { shown?.let { open(Intent(Intent.ACTION_VIEW, Uri.parse(it.notesUrl))) } }
        skip.setOnClickListener {
            shown?.let { Updates.skip(activity, it.version) }
            UpdateInstaller.reset(activity)
            hide()
        }
        later.setOnClickListener {
            Updates.laterThisLaunch = true
            UpdateInstaller.reset(activity)
            hide()
        }
    }

    fun onResume() {
        UpdateInstaller.addListener(listener)
        UpdateInstaller.setHost(activity)
        UpdateInstaller.resume(activity)
        refresh()
    }

    fun onPause() {
        UpdateInstaller.removeListener(listener)
        UpdateInstaller.setHost(null)
    }

    /** Show or hide to match the pending release, the skip, Later and the install's state. */
    fun refresh() {
        try {
            val s = UpdateInstaller.state
            if (s !is UpdateInstaller.State.Idle) return showState(s)
            val r = Updates.pending
            if (r != null && Updates.shouldShowPending(activity)) show(r) else hide()
        } catch (t: Throwable) {
            Log.w("MicroDeck", "update bar", t)
        }
    }

    fun show(r: UpdateCheck.Release) {
        shown = r
        if (UpdateInstaller.state !is UpdateInstaller.State.Idle) return refresh()
        text.text = activity.getString(R.string.update_available, r.version)
        buttons(download, notes, skip, later)
        detail.visibility = View.GONE
        progress.visibility = View.GONE
        root.visibility = View.VISIBLE
    }

    private fun showState(s: UpdateInstaller.State) {
        val version = (UpdateInstaller.release ?: shown)?.version.orEmpty()
        detail.visibility = View.GONE
        progress.visibility = View.GONE
        when (s) {
            is UpdateInstaller.State.Downloading -> {
                if (s.total > 0) {
                    val pct = (s.done * 100 / s.total).toInt().coerceIn(0, 100)
                    text.text = activity.getString(R.string.update_downloading_pct, version, pct)
                    progress.isIndeterminate = false
                    progress.progress = pct
                } else {
                    text.text = activity.getString(R.string.update_downloading, version)
                    progress.isIndeterminate = true
                }
                progress.visibility = View.VISIBLE
                buttons(cancel)
            }
            UpdateInstaller.State.Verifying -> {
                text.setText(R.string.update_verifying)
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
                buttons()
            }
            is UpdateInstaller.State.NeedsPermission -> {
                text.setText(if (s.declined) R.string.update_permission_declined else R.string.update_need_permission)
                detail(if (s.declined) R.string.update_permission_declined_detail else R.string.update_need_permission_detail)
                buttons(allow, browser, later)
            }
            UpdateInstaller.State.Installing -> {
                text.setText(R.string.update_installing)
                detail(R.string.update_installing_detail)
                buttons()
            }
            UpdateInstaller.State.Installed -> {
                text.setText(R.string.update_installed)
                buttons()
            }
            is UpdateInstaller.State.Failed -> {
                text.setText(R.string.update_failed_title)
                detail.text = activity.getString(s.message, s.detail ?: "no details")
                detail.visibility = View.VISIBLE
                buttons(retry, browser, later)
            }
            UpdateInstaller.State.Idle -> Unit
        }
        root.visibility = View.VISIBLE
    }

    private fun detail(res: Int) {
        detail.setText(res)
        detail.visibility = View.VISIBLE
    }

    /** Show exactly these buttons, in the layout's order. */
    private fun buttons(vararg visible: Button) {
        for (b in listOf(cancel, download, allow, retry, browser, notes, skip, later)) {
            b.visibility = if (b in visible) View.VISIBLE else View.GONE
        }
        // the first visible button sits flush with the bar's edge
        var first = true
        for (b in listOf(cancel, download, allow, retry, browser, notes, skip, later)) {
            if (b.visibility != View.VISIBLE) continue
            (b.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = if (first) 0 else (8 * activity.resources.displayMetrics.density).toInt()
                b.layoutParams = it
            }
            first = false
        }
    }

    fun hide() {
        root.visibility = View.GONE
    }

    private fun open(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("MicroDeck", "no browser for the update link", e)
            Toast.makeText(activity, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** For Open in browser when the release isn't known any more (after a restart). */
        private const val LATEST_APK = "https://github.com/Crazy8697/MicroDeck/releases/latest/download/MicroDeck.apk"
    }
}
