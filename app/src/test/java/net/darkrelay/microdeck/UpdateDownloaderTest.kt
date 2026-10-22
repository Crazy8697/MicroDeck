package net.darkrelay.microdeck

import net.darkrelay.microdeck.UpdateDownloader.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** The update download with fake connections: no network. */
class UpdateDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A connection that answers [code] with [stream] and never touches the network. */
    private class FakeConn(url: URL, val code: Int, val stream: InputStream, val length: Long) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = stream
        override fun getContentLengthLong() = length
    }

    private fun fake(code: Int = 200, body: ByteArray = ByteArray(0), length: Long = body.size.toLong(),
                     stream: InputStream = ByteArrayInputStream(body)) =
        UpdateDownloader(open = { FakeConn(it, code, stream, length) })

    private val url = "https://example.com/releases/download/v99.0.0/MicroDeck.apk"

    @Test fun downloadsAndHashes() {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val dest = tmp.root.resolve("updates/MicroDeck.apk")
        val seen = mutableListOf<Long>()
        val r = fake(body = body).download(url, dest, progress = { done, _ -> seen += done })
        assertTrue(r is Result.Done)
        assertEquals(UpdateVerify.sha256Hex(body), (r as Result.Done).sha256)
        assertTrue(dest.readBytes().contentEquals(body))
        assertEquals(body.size.toLong(), seen.last())
        assertFalse(tmp.root.resolve("updates/MicroDeck.apk.part").exists())
    }

    @Test fun shaTextIsFetched() {
        val r = fake(body = "abc *MicroDeck.apk".toByteArray()).fetchText("$url.sha256")
        assertEquals(Result.Text("abc *MicroDeck.apk"), r)
    }

    @Test fun httpErrorFails() {
        assertTrue(fake(code = 404).download(url, tmp.root.resolve("a.apk")) is Result.Failed)
        assertTrue(fake(code = 500).fetchText("$url.sha256") is Result.Failed)
    }

    @Test fun timeoutIsReportedAndLeavesNothing() {
        val stalls = object : InputStream() {
            var n = 0
            override fun read(): Int = throw SocketTimeoutException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ == 0) { b[off] = 1; return 1 }
                throw SocketTimeoutException("Read timed out")
            }
        }
        val dest = tmp.root.resolve("t.apk")
        assertEquals(Result.TimedOut, fake(length = 1000, stream = stalls).download(url, dest))
        assertFalse(dest.exists())
        assertFalse(tmp.root.resolve("t.apk.part").exists())
    }

    @Test fun cutMidDownloadFails() {
        // the connection ends after 10 of 1000 promised bytes
        val dest = tmp.root.resolve("c.apk")
        assertTrue(fake(body = ByteArray(10), length = 1000).download(url, dest) is Result.Failed)
        assertFalse(dest.exists())
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("reset")
        }
        assertTrue(fake(length = 1000, stream = broken).download(url, dest) is Result.Failed)
        assertFalse(dest.exists())
    }

    @Test fun cancelStopsAndLeavesNothing() {
        val body = ByteArray(1_000_000)
        var reads = 0
        val dest = tmp.root.resolve("x.apk")
        val r = fake(body = body).download(url, dest, progress = { _, _ -> reads++ }, cancelled = { reads > 3 })
        assertEquals(Result.Cancelled, r)
        assertFalse(dest.exists())
        assertFalse(tmp.root.resolve("x.apk.part").exists())
    }

    @Test fun oversizeIsRefused() {
        assertTrue(fake(body = ByteArray(10), length = 10).download(url, tmp.root.resolve("o.apk"), maxBytes = 5) is Result.Failed)
        assertTrue(fake(body = ByteArray(10), length = -1).download(url, tmp.root.resolve("o.apk"), maxBytes = 5) is Result.Failed)
    }
}
