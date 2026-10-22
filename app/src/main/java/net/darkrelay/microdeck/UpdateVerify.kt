package net.darkrelay.microdeck

import java.io.File
import java.security.MessageDigest

/**
 * The rules a downloaded update must pass before Android is asked to install it. No Android API here,
 * so it can be unit tested: the caller reads the facts (hashes, package name, versionCode, signers).
 */
object UpdateVerify {

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /** The hash in a .sha256 file ("<64 hex> *MicroDeck.apk" or just the hash), lower case; null if none. */
    fun parseSha256File(text: String?): String? {
        val first = text?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: return null
        return first.takeIf { HEX64.matches(it) }?.lowercase()
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** True only for two well-formed hashes that are equal. */
    fun hashMatches(actual: String?, expected: String?): Boolean =
        actual != null && expected != null && HEX64.matches(actual) && actual.equals(expected, ignoreCase = true)

    /** What an APK says about itself: package name, versionCode and signing certificate SHA-256s. */
    data class ApkFacts(
        val packageName: String?,
        val versionCode: Long,
        /** the certificate(s) signing this APK now */
        val signers: Set<String>,
        /** every certificate in its rotation history (the current one included) */
        val history: Set<String> = signers,
    )

    sealed class Problem {
        /** Android couldn't read the file as an APK. */
        object Unreadable : Problem()
        object WrongPackage : Problem()
        object NotNewer : Problem()
        object WrongSigner : Problem()
    }

    /**
     * Null when [apk] is a genuine newer MicroDeck: the same package, a higher versionCode, and signed by
     * the installed app's certificate (directly, or by a later key whose rotation history includes it).
     */
    fun checkApk(apk: ApkFacts?, installed: ApkFacts): Problem? {
        if (apk == null || apk.packageName.isNullOrEmpty() || apk.signers.isEmpty()) return Problem.Unreadable
        if (apk.packageName != installed.packageName) return Problem.WrongPackage
        if (apk.versionCode <= installed.versionCode) return Problem.NotNewer
        if (installed.signers.isEmpty()) return Problem.WrongSigner
        val sameKey = apk.signers == installed.signers
        // a rotated key is one new signer whose proof-of-rotation history includes the installed one
        val rotatedFrom = installed.signers.size == 1 && apk.signers.size == 1 && installed.signers.first() in apk.history
        return if (sameKey || rotatedFrom) null else Problem.WrongSigner
    }
}
