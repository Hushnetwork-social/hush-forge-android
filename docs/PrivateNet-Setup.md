# Neo3 Private-Net Setup

## Network

- Magic: `5195086`
- Host RPC: `http://127.0.0.1:10332`
- Android app RPC: supplied at build time with `FORGE_DEFAULT_RPC_URL` or
  Gradle property `forgeDefaultRpcUrl`

For the released Neon Mobile APK, use a trusted HTTPS RPC URL. The raw emulator
HTTP URLs can be reachable from Android, but Neon Mobile targets a modern Android
SDK and does not allow cleartext HTTP custom RPC URLs in the installed build.

## HTTPS Tunnel

Start a temporary HTTPS tunnel to the local Neo RPC:

```powershell
docker run -d --name forge-neo-rpc-tunnel cloudflare/cloudflared:latest tunnel --no-autoupdate --url http://host.docker.internal:10332
docker logs forge-neo-rpc-tunnel
```

Use the generated `https://*.trycloudflare.com/` URL in Neon Mobile and rebuild
the Android app with it:

```powershell
$env:FORGE_DEFAULT_RPC_URL='https://your-current-tunnel.trycloudflare.com/'
$env:FORGE_REOWN_PROJECT_ID='your-reown-project-id'
$env:FORGE_EXPECTED_NETWORK_MAGIC='5195086'
.\gradlew.bat :app:assembleDebug
```

Tunnel URLs are temporary. If the app reports a stale Cloudflare tunnel, restart
the tunnel and rebuild with the new URL.

Stop the tunnel after testing:

```powershell
docker rm -f forge-neo-rpc-tunnel
```

## Neon Mobile

Install Neon Wallet from the official Google Play package:

```powershell
adb shell am start -a android.intent.action.VIEW -d "market://details?id=io.cityofzion.neon"
```

Create a Neon custom network using the HTTPS tunnel URL, then import the
private-net PoC wallet. Do not store production keys in this PoC app or in the
private-net Neon wallet.
