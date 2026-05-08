# WalletConnect + Neon Mobile PoC

The Android app is a WalletConnect dApp using Reown Kotlin/AppKit. Neon Mobile is
the wallet/signing surface.

## Proven

- Connect from FORGE Android to Neon Mobile.
- Select the Neon account during WalletConnect approval.
- Read the WalletConnect session account from the approved session.
- Read the WalletConnect chain from the approved session.
- Validate the private-net RPC magic `5195086`.
- Load NEP-17 balances for the connected Neon account.

## Current Flow

1. Start the Neo3 private network container.
2. Expose the local Neo RPC through a trusted HTTPS URL for Neon Mobile.
3. Configure Neon Mobile with that HTTPS custom network.
4. Import the private-net PoC wallet into Neon Mobile.
5. Open FORGE Android and tap `Connect Neon`.
6. Approve the WalletConnect session in Neon Mobile.
7. Confirm FORGE shows `Wallet chain: neo3:private` and the Neon account address.
8. Use FORGE Android to send wallet requests; Neon Mobile handles approval.

The app also copies the WalletConnect URI to the Android clipboard. If Neon opens
without showing the approval screen, use Neon Mobile's dApp connection screen and
paste the copied URI manually. That is a current PoC fallback while we validate
Neon's direct `wc:` deep-link behavior.

## Next Checkpoint

The next proof point is a real write operation:

1. FORGE builds a contract invocation request.
2. FORGE sends it through WalletConnect/AppKit.
3. Neon Mobile opens the approval screen.
4. The user approves in Neon.
5. The transaction is signed and broadcast to `neo3:private`.
6. FORGE receives the result and refreshes state.
