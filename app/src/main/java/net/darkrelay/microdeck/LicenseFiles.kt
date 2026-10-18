package net.darkrelay.microdeck

/**
 * What the Licenses screen shows, in order: MicroDeck's own license, the third-party notices, then
 * every full license text. Each asset under assets/licenses/ is a copy of a file in the repository
 * root; LicenseFilesTest keeps the copies identical.
 */
object LicenseFiles {

    /** Shown on screen as a heading, read from assets/licenses/[asset], copied from [source]. */
    data class Entry(val title: String, val asset: String, val source: String)

    val ENTRIES = listOf(
        Entry("MicroDeck (MIT License)", "LICENSE.txt", "LICENSE"),
        Entry("Third-party notices", "THIRD-PARTY-NOTICES.md", "THIRD-PARTY-NOTICES.md"),
        Entry("Kotlin Standard Library: Apache License 2.0", "kotlin-stdlib-LICENSE.txt",
            "licenses/kotlin-stdlib-LICENSE.txt"),
        Entry("Kotlin Standard Library: NOTICE", "kotlin-stdlib-NOTICE.txt",
            "licenses/kotlin-stdlib-NOTICE.txt"),
        Entry("Kotlin stdlib, from GWT: Apache License 2.0", "kotlin-stdlib-third_party-gwt_license.txt",
            "licenses/kotlin-stdlib-third_party-gwt_license.txt"),
        Entry("Kotlin stdlib, from ThreeTen backport: BSD 3-Clause",
            "kotlin-stdlib-third_party-threetenbp_license.txt",
            "licenses/kotlin-stdlib-third_party-threetenbp_license.txt"),
        Entry("Kotlin stdlib, from Guava: Apache License 2.0", "kotlin-stdlib-third_party-guava_license.txt",
            "licenses/kotlin-stdlib-third_party-guava_license.txt"),
        Entry("Kotlin stdlib, from Boost: Boost Software License 1.0",
            "kotlin-stdlib-third_party-boost_LICENSE.txt",
            "licenses/kotlin-stdlib-third_party-boost_LICENSE.txt"),
        Entry("JetBrains Java Annotations: Apache License 2.0", "jetbrains-annotations-LICENSE.txt",
            "licenses/jetbrains-annotations-LICENSE.txt"),
    )

    const val ASSET_DIR = "licenses"
}
