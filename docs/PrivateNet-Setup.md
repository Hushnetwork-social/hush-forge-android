# Neo3 Private-Net Setup

## Network

- Magic: `5195086`
- Host RPC: `http://127.0.0.1:10332`
- Android app default RPC: configured in `app/build.gradle` as `DEFAULT_RPC_URL`

For the released Neon Mobile APK, use a trusted HTTPS RPC URL. The raw emulator
HTTP URLs can be reachable from Android, but Neon Mobile targets a modern Android
SDK and does not allow cleartext HTTP custom RPC URLs in the installed build.

## HTTPS Tunnel

Start a temporary HTTPS tunnel to the local Neo RPC:

```powershell
docker run -d --name forge-neo-rpc-tunnel cloudflare/cloudflared:latest tunnel --no-autoupdate --url http://host.docker.internal:10332
docker logs forge-neo-rpc-tunnel
```

Use the generated `https://*.trycloudflare.com/` URL in Neon Mobile and in
`DEFAULT_RPC_URL` if the tunnel URL changes.

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
