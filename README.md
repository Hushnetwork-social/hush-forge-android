# HushForgeAndroid

Native Android app for FORGE.

This repository starts with the FEAT-122 WalletConnect proof of work. It is not a
WebView shell and does not load the web FORGE UI. The app is a native Android
WalletConnect dApp using Reown Kotlin/AppKit, with Neon Mobile as the
wallet/signing surface.

## Current PoC

The current app proves:

- FORGE can connect to Neon Mobile through WalletConnect.
- Neon Mobile returns the selected account address.
- The session chain is `neo3:private`.
- FORGE validates the Neo3 private-net RPC magic `5195086`.
- Token balances are loaded only after the WalletConnect session provides the
  wallet chain and address.

The launcher display name is `FORGE`. The Gradle/internal project name is
`HushForgeAndroid`.

## Repository Shape

```text
app/        Android application source
docs/       PoC notes, private-net setup, and roadmap
gradle/     Gradle wrapper files
```

## Build

Use Android Studio's bundled Java runtime if `JAVA_HOME` is not already set:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug
```

Install and launch on the active emulator:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell monkey -p social.hushnetwork.forge -c android.intent.category.LAUNCHER 1
```

## Private-Net Testing

See:

- [docs/PrivateNet-Setup.md](docs/PrivateNet-Setup.md)
- [docs/POC-WalletConnect-Neon.md](docs/POC-WalletConnect-Neon.md)
- [docs/Roadmap.md](docs/Roadmap.md)
