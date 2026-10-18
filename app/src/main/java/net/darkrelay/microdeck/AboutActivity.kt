package net.darkrelay.microdeck

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Typeface
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast

/**
 * DarkRelay About screen, laid out like Lazarus PDF's About box. OK or Back returns to the camera,
 * which reopens in MainActivity.onResume.
 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            Log.w(TAG, "version lookup failed", e)
            null
        } ?: "?"
        findViewById<TextView>(R.id.about_version).text = getString(R.string.about_version, version)
        // the site's card icons: drop-shadow(0 0 14px rgba(25,194,230,.25)) on an 84 px icon, i.e. a
        // gaussian with sigma 7 px; scaled to the 64 dp icon that is sigma 5.33 dp, and RenderEffect
        // takes a radius where sigma = 0.57735 * radius + 0.5, so radius 8.4 dp
        val blur = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, GLOW_RADIUS_DP, resources.displayMetrics)
        findViewById<View>(R.id.about_icon_glow)
            .setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL))

        // "Updates: ..." with the label in bold, as in Lazarus PDF
        findViewById<TextView>(R.id.about_updates).text = SpannableStringBuilder().apply {
            bold(getString(R.string.about_updates_label))
            append(": ").append(getString(R.string.about_updates))
        }

        // "By DarkRelay · Version x" (DarkRelay bold and tappable) and the license line
        findViewById<TextView>(R.id.about_footer).apply {
            text = SpannableStringBuilder().apply {
                append(getString(R.string.about_by)).append(" ")
                val start = length
                bold(getString(R.string.about_darkrelay))
                setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = open(SITE_URL)
                    // like Lazarus PDF: plain bold text, no link colour or underline
                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                    }
                }, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" · ").append(getString(R.string.about_version, version))
                append("\n").append(getString(R.string.about_license))
                // "Source code: <link>", the link in Lazarus PDF's link colour
                append("\n").append(getString(R.string.about_source)).append(" ")
                val linkStart = length
                append(SOURCE_URL)
                setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = open(SOURCE_URL)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = LINK_COLOR
                        ds.isUnderlineText = true
                    }
                }, linkStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = 0
        }

        findViewById<Button>(R.id.about_licenses).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
        findViewById<Button>(R.id.about_support).setOnClickListener { open(SUPPORT_URL) }

        findViewById<CompoundButton>(R.id.about_check_startup).apply {
            isChecked = Updates.checkAtStartup(this@AboutActivity)
            setOnCheckedChangeListener { _, on -> Updates.setCheckAtStartup(this@AboutActivity, on) }
        }
        updateBar = UpdateBar(this, findViewById(R.id.update_bar))
        updateBar.refresh()
        val check = findViewById<Button>(R.id.about_check)
        check.setOnClickListener {
            // asking by hand shows the newest release again, even one skipped or put off with Later
            Updates.clearSkip(this)
            Updates.laterThisLaunch = false
            check.isEnabled = false
            check.setText(R.string.about_checking)
            Updates.check(this) { result ->
                if (isFinishing || isDestroyed) return@check
                check.isEnabled = true
                check.setText(R.string.about_check_now)
                when (result) {
                    is UpdateCheck.Result.Available -> updateBar.show(result.release)
                    UpdateCheck.Result.UpToDate -> toast(R.string.update_up_to_date)
                    UpdateCheck.Result.Failed -> toast(R.string.update_failed)
                }
            }
        }
        findViewById<Button>(R.id.about_ok).setOnClickListener { finish() }
    }

    private lateinit var updateBar: UpdateBar

    private fun toast(msg: Int) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun SpannableStringBuilder.bold(text: String) {
        val start = length
        append(text)
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Open a link in the browser; a toast instead of a crash when nothing can handle it. */
    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app for $url", e)
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MicroDeck"
        private const val SITE_URL = "https://darkrelay.net"
        private const val SUPPORT_URL = "https://buymeacoffee.com/crazy8697"
        private const val SOURCE_URL = "https://github.com/Crazy8697/MicroDeck"
        private const val LINK_COLOR = 0xFF7FD3FF.toInt()
        private const val GLOW_RADIUS_DP = 8.4f
    }
}
