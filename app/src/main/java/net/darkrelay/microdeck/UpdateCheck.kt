package net.darkrelay.microdeck

import org.json.JSONObject

/**
 * The rules for GitHub's releases/latest answer: which release, if any, to offer. No Android UI or
 * network here, so it can be unit tested. A release is offered only when it is not a draft or a
 * pre-release, its tag is a newer version than the installed one, and it carries both MicroDeck.apk
 * and MicroDeck.apk.sha256 (every release has exactly those two files). The APK is downloaded from
 * the tag's fixed path, DOWNLOAD_BASE/<tag>/MicroDeck.apk, not from a URL the answer supplies.
 */
object UpdateCheck {

    const val ASSET_APK = "MicroDeck.apk"
    const val ASSET_SHA256 = "MicroDeck.apk.sha256"
    const val DOWNLOAD_BASE = "https://github.com/Crazy8697/MicroDeck/releases/download"

    data class Release(val version: String, val apkUrl: String, val notesUrl: String)

    sealed class Result {
        /** Nothing newer to offer (also: the latest release isn't a usable one). */
        object UpToDate : Result()
        data class Available(val release: Release) : Result()
        /** No answer, a non-200 answer, or one that isn't the JSON we expect. */
        object Failed : Result()
    }

    // 1 to 4 numeric parts with an optional leading v; anything else (like 1.2.0-beta) is not a release version
    private val VERSION = Regex("""^[vV]?(\d{1,6})(?:\.(\d{1,6}))?(?:\.(\d{1,6}))?(?:\.(\d{1,6}))?$""")

    /** "v1.2" -> [1, 2, 0, 0]; null if it isn't a plain version. */
    fun parseVersion(s: String?): List<Int>? {
        val m = VERSION.matchEntire(s?.trim() ?: return null) ?: return null
        return m.groupValues.drop(1).map { if (it.isEmpty()) 0 else it.toInt() }
    }

    /** Negative, zero or positive like compareTo; null if either side isn't a version. */
    fun compareVersions(a: String?, b: String?): Int? {
        val x = parseVersion(a) ?: return null
        val y = parseVersion(b) ?: return null
        for (i in x.indices) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return 0
    }

    fun isNewer(candidate: String?, installed: String?): Boolean = (compareVersions(candidate, installed) ?: 0) > 0

    /** A skipped version stays quiet; anything newer than it is offered again. */
    fun shouldOffer(release: Release, skipped: String?): Boolean {
        if (skipped.isNullOrBlank()) return true
        return (compareVersions(release.version, skipped) ?: 1) > 0   // an unreadable skip doesn't hide anything
    }

    /**
     * The usable release in a releases/latest body, or null when the release is a draft, a pre-release,
     * has an odd tag or lacks one of the two assets. Throws on JSON that isn't an object.
     */
    fun parseRelease(body: String, downloadBase: String = DOWNLOAD_BASE): Release? {
        val o = JSONObject(body)
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) return null
        val tag = o.optString("tag_name", "").trim()
        val version = parseVersion(tag)?.let { tag.removePrefix("v").removePrefix("V") } ?: return null
        val notes = o.optString("html_url", "").takeIf { isWebUrl(it) } ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val names = (0 until assets.length()).mapNotNull { assets.optJSONObject(it)?.optString("name", "") }.toSet()
        if (ASSET_APK !in names || ASSET_SHA256 !in names) return null
        return Release(version, "$downloadBase/$tag/$ASSET_APK", notes)
    }

    /** What to show for a 200 answer's body, given the installed versionName. */
    fun evaluate(body: String, installed: String, downloadBase: String = DOWNLOAD_BASE): Result {
        val release = try {
            parseRelease(body, downloadBase)
        } catch (e: Exception) {
            return Result.Failed
        } ?: return Result.UpToDate
        return if (isNewer(release.version, installed)) Result.Available(release) else Result.UpToDate
    }

    private fun isWebUrl(s: String) = s.startsWith("https://") || s.startsWith("http://")
}
