package net.darkrelay.microdeck

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Opens the update APK in a browser, never in an app that claims github.com links: the GitHub app
 * hands such a link to a browser Custom Tab, where the "File might be harmful" prompt never shows
 * and the download stays .pending for good.
 */
object Browsers {

    /** A neutral web page: what handles it by default is the default browser. */
    private const val PROBE_URL = "https://example.com"

    /** The system's "Open with" pickers: what resolves when there is no default, not a browser. */
    private val PICKERS = setOf("android", "com.android.intentresolver")

    /** True if the resolved activity is an app (a browser), not the system chooser or resolver. */
    fun isRealBrowser(packageName: String?, activityName: String?): Boolean {
        if (packageName.isNullOrEmpty() || packageName in PICKERS) return false
        val cls = activityName.orEmpty()
        return !cls.endsWith("ResolverActivity") && !cls.endsWith("ChooserActivity")
    }

    /**
     * ACTION_VIEW for [url] aimed at the default browser as a normal tab. With no default browser:
     * the only browser, or a chooser of just the browsers; with none at all, the plain intent.
     */
    fun viewIntent(context: Context, url: String): Intent {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse(PROBE_URL)).addCategory(Intent.CATEGORY_BROWSABLE)
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

        val def = pm.resolveActivity(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            ?.activityInfo
        if (isRealBrowser(def?.packageName, def?.name)) return view.setPackage(def!!.packageName)

        val browsers = pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            .map { it.activityInfo }
            .filter { isRealBrowser(it.packageName, it.name) }
            .map { it.packageName }
            .distinct()
        return when (browsers.size) {
            0 -> view
            1 -> view.setPackage(browsers[0])
            else -> Intent.createChooser(Intent(view).setPackage(browsers[0]), context.getString(R.string.update_open_with))
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, browsers.drop(1).map { Intent(view).setPackage(it) }.toTypedArray())
        }
    }
}
