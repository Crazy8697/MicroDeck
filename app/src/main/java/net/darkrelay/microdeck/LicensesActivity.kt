package net.darkrelay.microdeck

import android.app.Activity
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView

/** MicroDeck's license and the third-party notices, read from bundled assets (fully offline). */
class LicensesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)
        val out = SpannableStringBuilder()
        for (e in LicenseFiles.ENTRIES) {
            val body = try {
                assets.open("${LicenseFiles.ASSET_DIR}/${e.asset}").bufferedReader().use { it.readText() }
            } catch (ex: Exception) {
                Log.w("MicroDeck", "license asset ${e.asset} missing", ex)
                getString(R.string.licenses_missing)
            }
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length
            out.append(e.title)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(1.3f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(getColor(R.color.darkrelay_cyan)), start, out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append("\n\n").append(body.trimEnd())
        }
        findViewById<TextView>(R.id.licenses_text).text = out
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
