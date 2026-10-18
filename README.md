# MicroDeck

Turns your Android phone into a magnifier for soldering and fine work. Free, open source, no telemetry.

## Download

**[Download MicroDeck.apk](https://github.com/Crazy8697/MicroDeck/releases/latest/download/MicroDeck.apk)**
(latest release) &nbsp;&middot;&nbsp; [All releases](https://github.com/Crazy8697/MicroDeck/releases)

Installing:

- Your browser may warn that the file "might be harmful". It says that about every APK downloaded
  outside an app store.
- The first time, Android asks you to allow installs from your browser ("Install unknown apps").
  Allow it, then open the downloaded file to install.
- Each release also has a `.sha256` file you can use to check the download.

MicroDeck checks this page for new versions and shows a bar when one is out; the update downloads
through your browser the same way.

## What it does

Pick the macro, main or telephoto lens, zoom with the slider or a pinch, and use autofocus or set the
focus by hand. Adjust exposure and white balance (and lock it), turn on the grid overlay to line up tiny
parts, and light your work with the torch, at an adjustable brightness on phones that support it.
Freeze or rotate the image, and save snapshots to your gallery (Pictures/MicroDeck).

The controls are large, so it also works well with a mouse through a screen mirror on a PC monitor.

## Privacy

No accounts, no ads, no telemetry. Everything stays on your phone. The only network request is the
update check, a look at this repository's latest release on GitHub; you can turn it off on the About
screen.

## Requirements

- Android 13 or newer
- A phone with a back camera

## Building from source

Open the project in Android Studio, or run `gradlew assembleDebug` with the Android SDK and JDK 17 or
newer installed. `gradlew assembleRelease` builds an unsigned release APK unless a `keystore.properties`
with your own signing key is present in the project root.

## License

MicroDeck is released under the MIT License, see [LICENSE](LICENSE). Third-party components that ship
with the app are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), with their full license
texts in [licenses](licenses).

Made by DarkRelay, https://darkrelay.net
