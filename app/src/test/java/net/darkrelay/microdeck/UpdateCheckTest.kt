package net.darkrelay.microdeck

import net.darkrelay.microdeck.UpdateCheck.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic releases only, versioned far ahead (99.x) so they never go stale. No network. */
class UpdateCheckTest {

    private fun body(
        tag: String = "v99.0.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<String> = listOf("MicroDeck.apk", "MicroDeck.apk.sha256", "MicroDeck-99.0.0.apk"),
        htmlUrl: String = "https://example.com/releases/tag/v99.0.0",
    ) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"$htmlUrl",
         "assets":[${assets.joinToString(",") {
            """{"name":"$it","browser_download_url":"https://example.com/download/$it"}"""
        }}]}
    """.trimIndent()

    @Test fun versionsCompareNumerically() {
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.0"))
        assertTrue(UpdateCheck.isNewer("v1.0", "0.99.99"))
        assertTrue(UpdateCheck.isNewer("99.0.1", "99.0.0"))
        assertFalse(UpdateCheck.isNewer("0.9.0", "0.10.0"))
        assertFalse(UpdateCheck.isNewer("1.2.0", "1.2"))
        assertEquals(0, UpdateCheck.compareVersions("v1.2", "1.2.0.0"))
    }

    @Test fun oddVersionsAreNotNewer() {
        assertNull(UpdateCheck.parseVersion("1.2.0-beta"))
        assertNull(UpdateCheck.parseVersion("latest"))
        assertNull(UpdateCheck.parseVersion(""))
        assertNull(UpdateCheck.parseVersion(null))
        assertFalse(UpdateCheck.isNewer("99.0.0-rc1", "0.9.0"))
        assertFalse(UpdateCheck.isNewer("99.0.0", "not a version"))
    }

    @Test fun newerReleaseWithBothAssetsIsOffered() {
        val r = UpdateCheck.evaluate(body(), "0.9.0")
        assertTrue(r is Result.Available)
        val rel = (r as Result.Available).release
        assertEquals("99.0.0", rel.version)
        assertEquals("https://example.com/download/MicroDeck.apk", rel.apkUrl)
        assertEquals("https://example.com/releases/tag/v99.0.0", rel.notesUrl)
    }

    @Test fun missingApkOrSha256IsNotOffered() {
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(assets = listOf("MicroDeck.apk.sha256")), "0.9.0"))
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(assets = listOf("MicroDeck.apk")), "0.9.0"))
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(assets = listOf("MicroDeck-99.0.0.apk")), "0.9.0"))
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(assets = emptyList()), "0.9.0"))
    }

    @Test fun draftsAndPreReleasesAreIgnored() {
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(draft = true), "0.9.0"))
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(prerelease = true), "0.9.0"))
    }

    @Test fun sameOrOlderVersionIsUpToDate() {
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(tag = "v0.9.0"), "0.9.0"))
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(tag = "0.8.5"), "0.9.0"))
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(tag = "v99.0.0"), "99.0.0"))
    }

    @Test fun badTagOrNotesUrlIsNotOffered() {
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(tag = "nightly"), "0.9.0"))
        assertEquals(Result.UpToDate, UpdateCheck.evaluate(body(htmlUrl = "javascript:alert(1)"), "0.9.0"))
    }

    @Test fun brokenJsonFails() {
        assertEquals(Result.Failed, UpdateCheck.evaluate("", "0.9.0"))
        assertEquals(Result.Failed, UpdateCheck.evaluate("not json", "0.9.0"))
        assertEquals(Result.Failed, UpdateCheck.evaluate("""{"tag_name":"v99.0.0","assets":[""", "0.9.0"))
        assertEquals(Result.Failed, UpdateCheck.evaluate("""[{"tag_name":"v99.0.0"}]""", "0.9.0"))
        // a 404 body from the API is valid JSON but no release
        assertEquals(Result.UpToDate, UpdateCheck.evaluate("""{"message":"Not Found","status":"404"}""", "0.9.0"))
    }

    @Test fun skipHidesThatVersionButNotNewerOnes() {
        val r99 = UpdateCheck.Release("99.0.0", "https://example.com/a", "https://example.com/n")
        val r991 = r99.copy(version = "99.0.1")
        assertTrue(UpdateCheck.shouldOffer(r99, null))
        assertTrue(UpdateCheck.shouldOffer(r99, ""))
        assertFalse(UpdateCheck.shouldOffer(r99, "99.0.0"))
        assertTrue(UpdateCheck.shouldOffer(r991, "99.0.0"))
        assertFalse(UpdateCheck.shouldOffer(r99, "99.0.1"))
        assertTrue(UpdateCheck.shouldOffer(r99, "garbage"))
    }
}
