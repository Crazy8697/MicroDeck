# MicroDeck

Turns your Android phone into a magnifier for soldering and fine work. Free, open source, no telemetry.

## Download

**[Download MicroDeck.apk](https://github.com/Crazy8697/MicroDeck/releases/latest/download/MicroDeck.apk)**
(latest release) &nbsp;&middot;&nbsp; [All releases](https://github.com/Crazy8697/MicroDeck/releases)

Installing:

- Your browser will say the file might be harmful; tap "Download anyway". It says that about every
  APK downloaded outside an app store. If the download sits at 100% and never finishes, open the link
  in your browser instead of the GitHub app.
- The first time, Android asks you to allow installs from your browser ("Install unknown apps").
  Allow it, then open the downloaded file to install.
- When you install, Google Play Protect may say "App blocked to protect your device" because it
  doesn't know the developer yet. Tap "More details", then "Install anyway".
- Each release also has a `.sha256` file you can use to check the download.

### Updates

MicroDeck checks this page for new versions (when it starts, which you can turn off on the About
screen, and when you tap Check for updates) and shows a bar when one is out. Tap **Download and
install**: MicroDeck downloads the new MicroDeck.apk from the release, checks it against the release's
`.sha256` file and checks that it's signed by the same developer, then hands it to Android. The first
time, Android asks you to allow MicroDeck to install apps ("Install unknown apps"); after that Android
asks you to confirm each update. If you'd rather not allow it, **Open in browser** downloads the update
the same way you installed MicroDeck.

### Install with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) is an open-source app that installs and updates
apps straight from GitHub releases. With it installed on your phone, open
**[Add MicroDeck to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Crazy8697/MicroDeck)**.

If you already installed MicroDeck from your browser, Obtainium can take over its updates: it's the
same app with the same signing key.

## What it does

Pick the macro, main or telephoto lens, zoom with the slider or a pinch, and use autofocus or set the
focus by hand. Adjust exposure and white balance (and lock it), turn on the grid overlay to line up tiny
parts, and light your work with the torch, at an adjustable brightness on phones that support it.
Freeze or rotate the image, and save snapshots to your gallery (Pictures/MicroDeck).

The controls are large, so it also works well with a mouse through PhoneDeck or another screen mirror
on a PC monitor.

## Permissions

- **Camera**: the magnifier itself.
- **Internet**: only the update check and, when you choose to update, the update download, both from
  this repository on GitHub.
- **Install unknown apps** (`REQUEST_INSTALL_PACKAGES`): only so MicroDeck can install its own updates.
  Android doesn't grant it until you allow it, and still asks you to confirm each update.

## Privacy

No accounts, no ads, no telemetry. Everything stays on your phone. The only network requests are the
update check, a look at this repository's latest release on GitHub (you can turn it off on the About
screen), and the update download when you choose to update.

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
