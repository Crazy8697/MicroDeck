package net.darkrelay.microdeck

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The update check: one anonymous GET of GitHub's releases/latest for MicroDeck, on a background
 * thread with short timeouts. Nothing is sent but the request itself (no IDs, nothing about the
 * device, photos or settings). Every failure ends as Result.Failed; nothing here can crash the app.
 * The URL is BuildConfig.UPDATE_URL: GitHub in every normal build, only a -PmicrodeckUpdateUrl test
 * build points elsewhere.
 */
object Updates {

    private const val TAG = "MicroDeck"
    private const val PREFS = "updates"
    private const val KEY_AT_STARTUP = "check_at_startup"
    private const val KEY_SKIPPED = "skipped_version"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000
    private const val MAX_BODY = 512 * 1024

    // per launch (process): the startup check runs at most once, Later lasts until the next launch
    @Volatile var startupCheckDone = false
    @Volatile var laterThisLaunch = false
    /** The release found this launch (startup or manual check), shown by whichever screen is up. */
    @Volatile var pending: UpdateCheck.Release? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "update-check").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun checkAtStartup(ctx: Context) = prefs(ctx).getBoolean(KEY_AT_STARTUP, true)
    fun setCheckAtStartup(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_AT_STARTUP, on).apply()
    fun skipped(ctx: Context): String? = prefs(ctx).getString(KEY_SKIPPED, null)
    fun skip(ctx: Context, version: String) = prefs(ctx).edit().putString(KEY_SKIPPED, version).apply()
    fun clearSkip(ctx: Context) = prefs(ctx).edit().remove(KEY_SKIPPED).apply()

    /** True when [pending] should be on screen right now. */
    fun shouldShowPending(ctx: Context): Boolean {
        val r = pending ?: return false
        return !laterThisLaunch && UpdateCheck.shouldOffer(r, skipped(ctx))
    }

    /** Run the check off the main thread; [done] gets the result on the main thread. */
    fun check(ctx: Context, done: (UpdateCheck.Result) -> Unit) {
        val app = ctx.applicationContext
        try {
            worker.execute {
                val result = try {
                    fetch(app)
                } catch (t: Throwable) {
                    Log.w(TAG, "update check failed: ${t.javaClass.simpleName}")
                    UpdateCheck.Result.Failed
                }
                if (result is UpdateCheck.Result.Available) pending = result.release
                main.post { try { done(result) } catch (t: Throwable) { Log.w(TAG, "update result", t) } }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "update check not started", t)
            main.post { done(UpdateCheck.Result.Failed) }
        }
    }

    private fun fetch(ctx: Context): UpdateCheck.Result {
        val conn = URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "MicroDeck/${BuildConfig.VERSION_NAME} (+https://darkrelay.net)")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "update check: HTTP $code")
                return UpdateCheck.Result.Failed
            }
            val body = conn.inputStream.use { input ->
                val bytes = input.readNBytes(MAX_BODY + 1)
                if (bytes.size > MAX_BODY) return UpdateCheck.Result.Failed
                String(bytes, Charsets.UTF_8)
            }
            val result = UpdateCheck.evaluate(body, BuildConfig.VERSION_NAME)
            Log.i(TAG, "update check: ${result.javaClass.simpleName}")
            return result
        } finally {
            conn.disconnect()
        }
    }
}
