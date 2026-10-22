package net.darkrelay.microdeck

import net.darkrelay.microdeck.UpdateVerify.ApkFacts
import net.darkrelay.microdeck.UpdateVerify.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a downloaded update must pass before Android is asked to install it. Synthetic data only. */
class UpdateVerifyTest {

    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)
    private val installed = ApkFacts("net.darkrelay.microdeck", 12, setOf(keyA))
    private fun apk(pkg: String? = "net.darkrelay.microdeck", code: Long = 9900, signers: Set<String> = setOf(keyA),
                    history: Set<String> = signers) = ApkFacts(pkg, code, signers, history)

    @Test fun sha256FileIsParsed() {
        val h = "0123456789abcdef".repeat(4)
        assertEquals(h, UpdateVerify.parseSha256File("$h *MicroDeck.apk"))
        assertEquals(h, UpdateVerify.parseSha256File("${h.uppercase()}  MicroDeck.apk\n"))
        assertEquals(h, UpdateVerify.parseSha256File(h))
        assertNull(UpdateVerify.parseSha256File("not a hash *MicroDeck.apk"))
        assertNull(UpdateVerify.parseSha256File(""))
        assertNull(UpdateVerify.parseSha256File(null))
    }

    @Test fun hashMatchAndMismatch() {
        val good = UpdateVerify.sha256Hex("MicroDeck 99.0.0".toByteArray())
        assertTrue(UpdateVerify.hashMatches(good, good.uppercase()))
        val tampered = UpdateVerify.sha256Hex("MicroDeck 99.0.0!".toByteArray())
        assertFalse(UpdateVerify.hashMatches(tampered, good))
        assertFalse(UpdateVerify.hashMatches(good, null))
        assertFalse(UpdateVerify.hashMatches(null, good))
        assertFalse(UpdateVerify.hashMatches("", ""))
    }

    @Test fun knownSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateVerify.sha256Hex(ByteArray(0)))
    }

    @Test fun genuineNewerUpdatePasses() {
        assertNull(UpdateVerify.checkApk(apk(), installed))
    }

    @Test fun wrongPackageIsRejected() {
        assertEquals(Problem.WrongPackage, UpdateVerify.checkApk(apk(pkg = "com.example.other"), installed))
    }

    @Test fun wrongSignerIsRejected() {
        assertEquals(Problem.WrongSigner, UpdateVerify.checkApk(apk(signers = setOf(keyB)), installed))
        assertEquals(Problem.WrongSigner, UpdateVerify.checkApk(apk(signers = setOf(keyA, keyB)), installed))
    }

    @Test fun rotatedKeyWithTheOldOneInItsHistoryPasses() {
        assertNull(UpdateVerify.checkApk(apk(signers = setOf(keyB), history = setOf(keyA, keyB)), installed))
    }

    @Test fun olderOrEqualVersionIsRejected() {
        assertEquals(Problem.NotNewer, UpdateVerify.checkApk(apk(code = 12), installed))
        assertEquals(Problem.NotNewer, UpdateVerify.checkApk(apk(code = 5), installed))
    }

    @Test fun unreadableApkIsRejected() {
        assertEquals(Problem.Unreadable, UpdateVerify.checkApk(null, installed))
        assertEquals(Problem.Unreadable, UpdateVerify.checkApk(apk(pkg = null), installed))
        assertEquals(Problem.Unreadable, UpdateVerify.checkApk(apk(signers = emptySet()), installed))
    }
}
