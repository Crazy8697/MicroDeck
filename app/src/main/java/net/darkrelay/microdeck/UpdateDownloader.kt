package net.darkrelay.microdeck

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest

/**
 * Plain HTTPS downloads for the update: the small .sha256 text and the APK, streamed to a file with
 * progress, a cancel check between reads, connect and read timeouts and a size cap. No Android API, so
 * it is unit tested with fake connections. Nothing but the request itself is sent.
 */
class UpdateDownloader(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val userAgent: String = "MicroDeck",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {

    sealed class Result {
        /** [sha256] is of the bytes written to the file. */
        data class Done(val file: File, val sha256: String) : Result()
        data class Text(val text: String) : Result()
        object Cancelled : Result()
        object TimedOut : Result()
        data class Failed(val reason: String) : Result()
    }

    @Volatile private var current: HttpURLConnection? = null

    /** Stop a running download from another thread (its blocking read ends with an error). */
    fun abort() {
        try { current?.disconnect() } catch (_: Throwable) {}
    }

    private fun connect(url: String): HttpURLConnection {
        val conn = open(URL(url))
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", userAgent)
        current = conn
        return conn
    }

    /** A small text file (the .sha256), at most [maxBytes]. */
    fun fetchText(url: String, maxBytes: Int = 4096, cancelled: () -> Boolean = { false }): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = connect(url)
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return Result.Failed("HTTP $code")
            val bytes = conn.inputStream.use { it.readNBytes(maxBytes + 1) }
            when {
                cancelled() -> Result.Cancelled
                bytes.size > maxBytes -> Result.Failed("too large")
                else -> Result.Text(String(bytes, Charsets.UTF_8))
            }
        } catch (e: SocketTimeoutException) {
            if (cancelled()) Result.Cancelled else Result.TimedOut
        } catch (e: IOException) {
            if (cancelled()) Result.Cancelled else Result.Failed(e.javaClass.simpleName)
        } finally {
            current = null
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * Stream [url] into [dest] (through dest.part, renamed only when complete). [progress] gets
     * (bytes so far, total or -1). A cancel, timeout, short read or error removes the partial file.
     */
    fun download(
        url: String,
        dest: File,
        maxBytes: Long = 64L * 1024 * 1024,
        progress: (Long, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Result {
        val part = File(dest.parentFile, dest.name + ".part")
        var conn: HttpURLConnection? = null
        var ok = false
        try {
            dest.parentFile?.mkdirs()
            part.delete()
            conn = connect(url)
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return Result.Failed("HTTP $code")
            val total = conn.contentLengthLong
            if (total > maxBytes) return Result.Failed("too large")
            val md = MessageDigest.getInstance("SHA-256")
            var got = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    progress(0, total)
                    while (true) {
                        if (cancelled()) return Result.Cancelled
                        val n = input.read(buf)
                        if (n < 0) break
                        got += n
                        if (got > maxBytes) return Result.Failed("too large")
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        progress(got, total)
                    }
                }
            }
            if (cancelled()) return Result.Cancelled
            if (total >= 0 && got != total) return Result.Failed("short download ($got of $total)")
            dest.delete()
            if (!part.renameTo(dest)) return Result.Failed("couldn't save")
            ok = true
            return Result.Done(dest, UpdateVerify.hex(md.digest()))
        } catch (e: SocketTimeoutException) {
            return if (cancelled()) Result.Cancelled else Result.TimedOut
        } catch (e: IOException) {
            return if (cancelled()) Result.Cancelled else Result.Failed(e.javaClass.simpleName)
        } finally {
            current = null
            try { conn?.disconnect() } catch (_: Throwable) {}
            if (!ok) part.delete()
        }
    }
}
