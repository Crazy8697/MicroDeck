package net.darkrelay.microdeck

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app update: download the release's MicroDeck.apk and MicroDeck.apk.sha256 into cacheDir/updates,
 * check the hash, check that the APK is a newer MicroDeck signed with the installed app's key, get the
 * user's one-time OK for installs (Settings, "Install unknown apps"), then hand it to Android's
 * PackageInstaller in a session. Android shows its own confirm screen unless it allows a self-update
 * without one. Every step is caught: a failed update ends as a message in the update bar, never a crash.
 *
 * The verified file survives the app being restarted (Android may do that when the install permission
 * changes): its name and hash are kept in prefs, and the next launch continues from there.
 */
object UpdateInstaller {

    private const val TAG = "MicroDeck"
    private const val DIR = "updates"
    private const val APK = "MicroDeck.apk"
    private const val PREFS = "update_install"
    private const val KEY_SHA = "sha256"
    private const val KEY_ASKED = "asked_permission"
    const val ACTION_STATUS = "net.darkrelay.microdeck.INSTALL_STATUS"

    sealed class State {
        object Idle : State()
        /** [done] of [total] bytes ([total] -1 when unknown). */
        data class Downloading(val done: Long, val total: Long) : State()
        object Verifying : State()
        /** Verified and waiting for "Install unknown apps"; [declined] once the user came back without it. */
        data class NeedsPermission(val declined: Boolean) : State()
        object Installing : State()
        object Installed : State()
        /** [message] is a string resource; [detail] goes into it as %1$s where it has one. */
        data class Failed(val message: Int, val detail: String? = null) : State()
    }

    @Volatile var state: State = State.Idle
        private set
    /** The release being installed (for Retry and Open in browser). */
    @Volatile var release: UpdateCheck.Release? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "update-install").apply { isDaemon = true } }
    private val cancel = AtomicBoolean(false)
    private var downloader: UpdateDownloader? = null
    private val listeners = mutableListOf<() -> Unit>()
    private var host: WeakReference<Activity>? = null
    private var cleaned = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    /** The visible activity, used to show Android's confirm screen. */
    fun setHost(a: Activity?) { host = a?.let { WeakReference(it) } }

    private fun set(s: State) {
        state = s
        main.post { listeners.toList().forEach { try { it() } catch (t: Throwable) { Log.w(TAG, "update listener", t) } } }
    }

    fun busy() = state is State.Downloading || state === State.Verifying || state === State.Installing

    private fun dir(ctx: Context) = File(ctx.cacheDir, DIR)
    private fun apkFile(ctx: Context) = File(dir(ctx), APK)
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clearFiles(ctx: Context) {
        try { dir(ctx).listFiles()?.forEach { it.delete() } } catch (t: Throwable) { Log.w(TAG, "update cleanup", t) }
        prefs(ctx).edit().clear().apply()
    }

    /**
     * Once per process, on startup: an update verified before a restart continues (or waits for the
     * install permission); anything else in cacheDir/updates, a finished update included, is removed.
     */
    fun startup(ctx: Context) {
        if (cleaned) return
        cleaned = true
        // a new process has no install in flight: any session of ours is left over from before
        try {
            val installer = ctx.packageManager.packageInstaller
            installer.mySessions.forEach { try { installer.abandonSession(it.sessionId) } catch (_: Throwable) {} }
        } catch (t: Throwable) {
            Log.w(TAG, "update session cleanup", t)
        }
        try {
            val sha = prefs(ctx).getString(KEY_SHA, null)
            val file = apkFile(ctx)
            if (sha != null && file.isFile && UpdateVerify.hashMatches(UpdateVerify.sha256Hex(file), sha) &&
                verifyApk(ctx, file) == null) {
                Log.i(TAG, "update: a verified download is waiting")
                set(State.NeedsPermission(declined = prefs(ctx).getBoolean(KEY_ASKED, false)))
                resume(ctx)
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "update startup", t)
        }
        clearFiles(ctx)
    }

    /** Start downloading and installing [r]. */
    fun start(ctx: Context, r: UpdateCheck.Release) {
        if (busy()) return
        val app = ctx.applicationContext
        release = r
        cancel.set(false)
        set(State.Downloading(0, -1))
        worker.execute { runCatchingAll(app) { download(app, r) } }
    }

    /** Cancel a running download. */
    fun cancel() {
        cancel.set(true)
        downloader?.abort()
    }

    /** Back to the plain offer (after a failure or a cancel). */
    fun reset(ctx: Context) {
        if (busy()) return
        clearFiles(ctx)
        set(State.Idle)
    }

    private fun runCatchingAll(ctx: Context, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "update failed", t)
            clearFiles(ctx)
            set(State.Failed(R.string.update_err_generic, t.javaClass.simpleName))
        }
    }

    private fun download(ctx: Context, r: UpdateCheck.Release) {
        clearFiles(ctx)
        val dl = UpdateDownloader(userAgent = "MicroDeck/${BuildConfig.VERSION_NAME} (+https://darkrelay.net)")
        downloader = dl
        val cancelled = { cancel.get() }

        val expected = when (val s = dl.fetchText(r.apkUrl + ".sha256", cancelled = cancelled)) {
            is UpdateDownloader.Result.Text -> UpdateVerify.parseSha256File(s.text)
            else -> return downloadEnded(ctx, s)
        }
        if (expected == null) {
            clearFiles(ctx)
            return set(State.Failed(R.string.update_err_hash))
        }
        var lastPct = -1
        val got = dl.download(r.apkUrl, apkFile(ctx), progress = { done, total ->
            val pct = if (total > 0) (done * 100 / total).toInt() else -1
            if (pct != lastPct) { lastPct = pct; set(State.Downloading(done, total)) }
        }, cancelled = cancelled)
        downloader = null
        if (got !is UpdateDownloader.Result.Done) return downloadEnded(ctx, got)

        set(State.Verifying)
        if (!UpdateVerify.hashMatches(got.sha256, expected)) {
            Log.w(TAG, "update: hash mismatch")
            clearFiles(ctx)
            return set(State.Failed(R.string.update_err_hash))
        }
        verifyApk(ctx, got.file)?.let { problem ->
            Log.w(TAG, "update: rejected (${problem.javaClass.simpleName})")
            clearFiles(ctx)
            return set(State.Failed(when (problem) {
                UpdateVerify.Problem.WrongSigner -> R.string.update_err_signer
                UpdateVerify.Problem.NotNewer -> R.string.update_err_not_newer
                else -> R.string.update_err_not_microdeck
            }))
        }
        prefs(ctx).edit().putString(KEY_SHA, expected).putBoolean(KEY_ASKED, false).apply()
        if (!ctx.packageManager.canRequestPackageInstalls()) return set(State.NeedsPermission(declined = false))
        install(ctx)
    }

    private fun downloadEnded(ctx: Context, r: UpdateDownloader.Result) {
        downloader = null
        clearFiles(ctx)
        set(when (r) {
            UpdateDownloader.Result.Cancelled -> State.Failed(R.string.update_err_cancelled_download)
            UpdateDownloader.Result.TimedOut -> State.Failed(R.string.update_err_network)
            is UpdateDownloader.Result.Failed -> State.Failed(R.string.update_err_network)
            else -> State.Failed(R.string.update_err_generic)
        })
    }

    /** Null when the file is a newer MicroDeck signed like this app; otherwise why not. */
    private fun verifyApk(ctx: Context, file: File): UpdateVerify.Problem? {
        val pm = ctx.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val installed = facts(pm.getPackageInfo(ctx.packageName, flags)) ?: return UpdateVerify.Problem.Unreadable
        val apk = try { facts(pm.getPackageArchiveInfo(file.path, flags)) } catch (t: Throwable) { null }
        return UpdateVerify.checkApk(apk, installed)
    }

    private fun facts(pi: PackageInfo?): UpdateVerify.ApkFacts? {
        pi ?: return null
        val si = pi.signingInfo ?: return UpdateVerify.ApkFacts(pi.packageName, pi.longVersionCode, emptySet())
        val current = si.apkContentsSigners.orEmpty().map { UpdateVerify.sha256Hex(it.toByteArray()) }.toSet()
        val history = if (si.hasMultipleSigners()) current
                      else current + si.signingCertificateHistory.orEmpty().map { UpdateVerify.sha256Hex(it.toByteArray()) }
        return UpdateVerify.ApkFacts(pi.packageName, pi.longVersionCode, current, history)
    }

    /** Open Android's "Install unknown apps" page for MicroDeck. */
    fun openPermissionSettings(a: Activity) {
        prefs(a).edit().putBoolean(KEY_ASKED, true).apply()
        try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
        } catch (t: Throwable) {
            Log.w(TAG, "no install-sources settings page", t)
            set(State.Failed(R.string.update_err_generic, t.javaClass.simpleName))
        }
    }

    /** When a screen comes back: if the user just allowed installs, carry on by itself. */
    @Synchronized fun resume(ctx: Context) {
        val s = state as? State.NeedsPermission ?: return
        val app = ctx.applicationContext
        if (app.packageManager.canRequestPackageInstalls()) {
            // claim it now: startup and the screen's onResume both get here, and only one may install
            set(State.Installing)
            worker.execute { runCatchingAll(app) { if (apkFile(app).isFile) install(app) else set(State.Failed(R.string.update_err_generic)) } }
        } else if (!s.declined && prefs(app).getBoolean(KEY_ASKED, false)) {
            set(State.NeedsPermission(declined = true))
        }
    }

    private fun install(ctx: Context) {
        set(State.Installing)
        val file = apkFile(ctx)
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(file.length())
            // Android skips its confirm screen for a self-update only when it allows that; otherwise it asks
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // foreground: the result (and Android's confirm screen) shouldn't wait behind background broadcasts
                val status = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_STATUS)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                // mutable: Android writes the result (and its confirm screen) into this intent
                val pi = PendingIntent.getBroadcast(ctx, id, status,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pi.intentSender)
            }
            Log.i(TAG, "update: session $id committed")
        } catch (t: Throwable) {
            try { installer.abandonSession(id) } catch (_: Throwable) {}
            throw t
        }
    }

    /** From InstallResultReceiver. */
    fun onStatus(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "update: install status $status ${msg.orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) return set(State.Failed(R.string.update_err_install, "no confirm screen"))
                val a = host?.get()
                try {
                    if (a != null && !a.isFinishing) a.startActivity(confirm)
                    else ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (t: Throwable) {
                    Log.w(TAG, "confirm screen", t)
                    set(State.Failed(R.string.update_err_install, t.javaClass.simpleName))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                clearFiles(ctx)
                // Android usually delivers this to a fresh process already running the new version:
                // then there is nothing to say. Only an old process still running asks for a reopen.
                val installed = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
                } catch (t: Throwable) { -1L }
                set(if (installed == BuildConfig.VERSION_CODE.toLong()) State.Idle else State.Installed)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // the user said no: forget the download, so the next launch doesn't start it again
                clearFiles(ctx)
                set(State.Failed(R.string.update_err_cancelled_install))
            }
            else -> {
                clearFiles(ctx)
                set(State.Failed(R.string.update_err_install, msg ?: "status $status"))
            }
        }
    }
}
