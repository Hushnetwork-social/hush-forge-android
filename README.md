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

The app no longer ships with a baked-in private RPC tunnel or Reown project id.
Set environment-specific values before building a runnable app:

```powershell
$env:FORGE_DEFAULT_RPC_URL='https://your-private-rpc.example/'
$env:FORGE_REOWN_PROJECT_ID='your-reown-project-id'
$env:FORGE_EXPECTED_NETWORK_MAGIC='5195086'
.\gradlew.bat :app:assembleDebug
```

If `FORGE_DEFAULT_RPC_URL` is missing, the app shows a configuration error
instead of trying to read chain data. If `FORGE_REOWN_PROJECT_ID` is missing,
read-only RPC screens can load but Neon Mobile WalletConnect is disabled.

Install and launch on the active emulator:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell monkey -p social.hushnetwork.forge -c android.intent.category.LAUNCHER 1
```

## Market Activity

The native Trade tab can use a FORGE market activity API when one is configured:

```text
/api/markets/{tokenHash}/activity?routerHash={routerHash}
```

The API is optional for now. Configure it at build time when that service is
available:

```powershell
$env:FORGE_ACTIVITY_API_BASE_URL='https://your-forge-api.example'
.\gradlew.bat :app:assembleDebug
```

If the API base is intentionally blank, the app falls back to direct Neo RPC
event replay and builds the minimum 15m candle model from the current FORGE
private-net trade events. The future API path can add multiple intervals,
server-side caching, richer history, holders, and trader rankings.

## Private-Net Testing

See:

- [docs/PrivateNet-Setup.md](docs/PrivateNet-Setup.md)
- [docs/POC-WalletConnect-Neon.md](docs/POC-WalletConnect-Neon.md)
- [docs/Mobile-Harness-Wallet.md](docs/Mobile-Harness-Wallet.md)
- [docs/Roadmap.md](docs/Roadmap.md)
