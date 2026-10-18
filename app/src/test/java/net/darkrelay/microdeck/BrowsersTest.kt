package net.darkrelay.microdeck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which resolved activity counts as the default browser for the update Download. */
class BrowsersTest {

    @Test fun browsersAreReal() {
        assertTrue(Browsers.isRealBrowser("com.brave.browser", "com.google.android.apps.chrome.IntentDispatcher"))
        assertTrue(Browsers.isRealBrowser("com.android.chrome", "com.google.android.apps.chrome.IntentDispatcher"))
        assertTrue(Browsers.isRealBrowser("org.mozilla.firefox", "org.mozilla.fenix.IntentReceiverActivity"))
    }

    @Test fun theSystemResolverIsNot() {
        // what resolves on a phone with no default browser
        assertFalse(Browsers.isRealBrowser("android", "com.android.internal.app.ResolverActivity"))
        assertFalse(Browsers.isRealBrowser("com.android.intentresolver", "com.android.intentresolver.ChooserActivity"))
        assertFalse(Browsers.isRealBrowser("com.example.oem", "com.example.oem.ResolverActivity"))
    }

    @Test fun nothingResolvedIsNot() {
        assertFalse(Browsers.isRealBrowser(null, null))
        assertFalse(Browsers.isRealBrowser("", "x"))
    }
}
