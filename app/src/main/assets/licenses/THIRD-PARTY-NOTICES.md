# Third-party notices

MicroDeck is released under the MIT License (see `LICENSE`). The app itself uses only the Android
framework. The release APK also ships the components below, pulled in by the Kotlin build. This list
comes from the release runtime classpath and from inspecting the built APK. JUnit and Hamcrest are
used only for unit tests and are not in the app.

Full license texts are in the `licenses/` folder and inside the app (About, Licenses).

## Kotlin Standard Library

- Artifact: `org.jetbrains.kotlin:kotlin-stdlib` 2.2.10
- License: Apache License, Version 2.0 (`licenses/kotlin-stdlib-LICENSE.txt`)
- Source: https://github.com/JetBrains/kotlin (tag v2.2.10)
- NOTICE: `licenses/kotlin-stdlib-NOTICE.txt`

Copyright 2010-2024 JetBrains s.r.o and respective authors and developers.

Parts of the standard library are derived from other projects, as listed in the Kotlin
repository's `license/README.md`:

| Part | Derived from | License | Text |
|---|---|---|---|
| `kotlin.collections` | GWT, (C) 2007-08 Google Inc. | Apache License 2.0 | `licenses/kotlin-stdlib-third_party-gwt_license.txt` |
| `kotlin.time` | ThreeTen backport, Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos | BSD 3-Clause | `licenses/kotlin-stdlib-third_party-threetenbp_license.txt` |
| unsigned number helpers (JVM) | Guava's UnsignedLongs, (C) 2011 The Guava Authors | Apache License 2.0 | `licenses/kotlin-stdlib-third_party-guava_license.txt` |
| math helpers (JVM) | Boost special math functions, Copyright Eric Ford & Hubert Holin 2001 | Boost Software License 1.0 | `licenses/kotlin-stdlib-third_party-boost_LICENSE.txt` |

## JetBrains Java Annotations

- Artifact: `org.jetbrains:annotations` 13.0 (a dependency of the Kotlin standard library)
- License: Apache License, Version 2.0 (`licenses/jetbrains-annotations-LICENSE.txt`)
- Source: https://github.com/JetBrains/java-annotations (version 13.0 was published from
  https://github.com/JetBrains/intellij-community)

Copyright 2000-2016 JetBrains s.r.o.
