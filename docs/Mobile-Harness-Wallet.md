# Local Mobile Harness Wallet

The Android app supports two WalletConnect pairing modes:

- Real wallet mode: `FORGE` opens Neon Mobile and the user approves manually.
- Harness mode: `FORGE` posts its WalletConnect URI to a local harness endpoint.

Harness mode is the CI/E2E path. It lets GitHub Actions run Android integration
tests without installing Neon Mobile or approving prompts by hand.

## Harness Contract

The harness endpoint must expose:

```text
POST /pair
Content-Type: application/json

{ "uri": "wc:..." }
```

The existing FORGE web harness already implements this endpoint in
`hush-forge/tools/forge-wallet-harness/dev-server.ts`.

## Emulator URL

When the harness runs on the CI/host machine and the Android app runs in the
emulator, the Android app must use the emulator host alias:

```text
http://10.0.2.2:32103/pair
```

## Build With Harness Pairing

Set the build-time environment variable before assembling the debug APK:

```powershell
$env:FORGE_MOBILE_WALLET_HARNESS_PAIR_URL='http://10.0.2.2:32103/pair'
.\gradlew.bat :app:assembleDebug
```

With this variable unset, the app remains in real Neon Mobile mode.

## CI Shape

1. Start the Neo3 private network.
2. Start the local WalletConnect relay and wallet harness.
3. Build the Android APK with `FORGE_MOBILE_WALLET_HARNESS_PAIR_URL`.
4. Install and launch the APK on the emulator.
5. Tap `Connect`.
6. The Android app sends the WalletConnect URI to the harness.
7. The harness approves `neo3:private` for the default funded account.
8. Android E2E can verify Pairs, Tokens, Admin visibility, and later write
   operations without Neon Mobile.

## Shared Behavior

The final mobile app and the PoC diagnostics screen both use the same harness
pairing switch. This keeps low-level WalletConnect debugging available while
the main app evolves into the production mobile UI.
