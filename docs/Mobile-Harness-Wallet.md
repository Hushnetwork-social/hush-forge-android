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

The shared FORGE wallet harness implements this endpoint in the public
`Hushnetwork-social/hush-forge-wallet-harness` repository.

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

GitHub Actions should stay narrow because the account has limited hosted
minutes. The default CI path builds the APK. The emulator path is one happy-path
smoke test on `master`/`main` or manual `workflow_dispatch`, not the full mobile
E2E matrix. Run broad E2E locally.

1. Start the Neo3 private network.
2. Start the local WalletConnect relay and wallet harness from
   `hush-forge-wallet-harness`.
3. Build the Android APK with:
   - `FORGE_DEFAULT_RPC_URL=http://10.0.2.2:10332`
   - `FORGE_REOWN_RELAY_URL=ws://10.0.2.2:32102?projectId=forge-local-project`
   - `FORGE_MOBILE_WALLET_HARNESS_PAIR_URL=http://10.0.2.2:32103/pair`
4. Install and launch the APK on the emulator.
5. Tap `Connect`.
6. The Android app sends the WalletConnect URI to the harness.
7. The harness approves `neo3:private` for the default funded account.
8. The CI smoke verifies connection and token loading without Neon Mobile.

## Shared Behavior

The final mobile app and the PoC diagnostics screen both use the same harness
pairing switch. This keeps low-level WalletConnect debugging available while
the main app evolves into the production mobile UI.
