package net.darkrelay.microdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Licenses screen's assets must be exact copies of the repository's license files. */
class LicenseFilesTest {

    // unit tests run with the app module as the working directory
    private val repo = File("..").canonicalFile
    private val assets = File("src/main/assets/${LicenseFiles.ASSET_DIR}")

    private fun text(f: File) = f.readText().replace("\r\n", "\n")

    @Test fun everyEntryHasAnAssetMatchingItsSource() {
        for (e in LicenseFiles.ENTRIES) {
            val asset = File(assets, e.asset)
            val source = File(repo, e.source)
            assertTrue("missing asset ${e.asset}", asset.isFile)
            assertTrue("missing source ${e.source}", source.isFile)
            assertEquals("asset ${e.asset} differs from ${e.source}", text(source), text(asset))
        }
    }

    @Test fun everyLicenseTextIsListed() {
        val listed = LicenseFiles.ENTRIES.map { it.source }.toSet()
        val onDisk = File(repo, "licenses").listFiles().orEmpty().map { "licenses/${it.name}" }
        for (f in onDisk) assertTrue("$f is not shown on the Licenses screen", f in listed)
        assertTrue(listed.containsAll(listOf("LICENSE", "THIRD-PARTY-NOTICES.md")))
    }

    @Test fun ourLicenseIsMit() {
        val mit = text(File(assets, "LICENSE.txt"))
        assertTrue(mit.startsWith("MIT License"))
        assertTrue(mit.contains("Copyright (c) 2026 DarkRelay"))
    }
}
