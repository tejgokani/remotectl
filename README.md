# remotectl

Control your laptop from your phone, from anywhere. Lock it, watch live stats, see installed apps and
launch or quit them, and open a terminal. **One phone controls as many laptops as you like** and
switches between them from the top bar.

```
Android app ──WSS──▶  relay (Cloudflare)  ◀──WSS── remotectl (each laptop)
            └──────── Noise_XX end-to-end encrypted ────────┘
```

The laptop only makes an **outbound** connection: no open ports, no port forwarding, works behind any
NAT. The relay forwards ciphertext and cannot read or forge commands.

## Set it up (5 minutes)

**1. Deploy your own relay** (free Cloudflare plan is enough):

```bash
cd relay-cloudflare && pnpm install && pnpm exec wrangler login && pnpm exec wrangler deploy
# prints https://remotectl-relay.<you>.workers.dev
```

**2. Install the agent on each laptop** and pair it.

macOS:

```bash
brew install tejgokani/remotectl/remotectl
remotectl pair --relay wss://remotectl-relay.<you>.workers.dev   # shows a QR code
remotectl install                               # start now and at every login
```

Windows — one PowerShell command downloads it to a fixed path, puts it on your PATH, and pairs:

```powershell
&([scriptblock]::Create((irm https://raw.githubusercontent.com/tejgokani/remotectl/main/scripts/install.ps1))) -Relay wss://remotectl-relay.<you>.workers.dev
```

(Drop `-Relay ...` to just install without pairing yet; re-run the same command any time to update —
it's idempotent.)

Linux — no installer script yet; grab `remotectl-*-linux-x64.tar.gz` from the
[releases page](https://github.com/tejgokani/remotectl/releases), then `pair` the same way as macOS
(auto-start isn't wired up for Linux yet — see Limits).

**3. Install the Android app** (`remotectl-<version>.apk` on the
[releases page](https://github.com/tejgokani/remotectl/releases)) and tap **Add laptop → Scan QR code**.
Repeat step 2 on other laptops to add them; use the name in the top bar to switch.

Other commands: `remotectl status`, `phones`, `unpair <n>|--all`, `uninstall`,
`config set terminal on` (the remote terminal is **off by default**).

## What you can do from the phone

| | |
|---|---|
| Lock | locks the laptop screen |
| Stats | live CPU (per core), memory, disk, battery, network, uptime, busiest processes |
| Apps | list installed apps, open, quit, force quit (force quit needs fingerprint/PIN) |
| Terminal | full terminal (xterm.js) with Esc/Tab/Ctrl/arrow keys; needs fingerprint/PIN each time |
| Switching | one phone, many laptops; online status shown per laptop |

## Security model

- **Pairing:** the QR carries the laptop's public key and a one-time code (10 chars, 5 min, 5 attempts).
- **Both sides pin keys:** the phone pins each laptop's key; each laptop pins the phones you paired.
  An unpaired phone is refused even if it somehow learned the relay secret.
- **The relay is untrusted:** its secret only gates room access. Everything else is Noise_XX
  (X25519, ChaCha20-Poly1305, BLAKE2s) end to end.
- **Terminal is opt-in** per laptop. `remotectl unpair` cuts an open session within ~20 s.
- **Runs as you**, in your login session (LaunchAgent / logon task), never as root or SYSTEM.
- Apps are only launched or quit by id from the agent's own scan of installed apps, never by free-form
  input from the phone.

## Limits

- A sleeping or shut-down laptop can't be reached; the phone shows it as offline.
- macOS is confirmed working end to end (pair, stats, apps, terminal, lock).
- Windows compiles clean and pairing has been run on a real machine; stats/apps/terminal/lock on a
  real Windows laptop are implemented but not yet confirmed by anyone.
- Linux compiles and can pair, show stats, and run the terminal, but has no app list, lock, or
  auto-start yet (those live in per-OS modules — `crates/agent/src/{apps,lock,install}.rs`).
- The Android app needs Google Play services for the QR scanner (or paste the invite instead).

## Layout

| path | what |
|---|---|
| `crates/agent` | `remotectl`: laptop agent + CLI (Rust) |
| `crates/protocol` | wire messages, Noise channel, invite format (Rust) |
| `crates/relay` | self-hostable relay for local dev / a VPS (Rust, axum) |
| `relay-cloudflare` | the production relay: Worker + one Durable Object per laptop (TypeScript) |
| `crates/phone-sim` | `remotectl-sim`: command-line "phone" used for testing |
| `android/core` | Kotlin: Noise_XX, protocol, device vault. Pure JVM, tested against the real agent |
| `android/app` | Jetpack Compose app |

## Develop

```bash
cargo test                                   # protocol tests
cargo run -p remotectl-relay -- 127.0.0.1:8787   # local relay (or: cd relay-cloudflare && pnpm dev)
cd android && ./gradlew :core:test           # Kotlin unit tests
# interop test against a real agent (start `remotectl pair` + `remotectl run` first):
REMOTECTL_INVITE='remotectl://p/…' ./gradlew :core:test
./gradlew :app:assembleDebug                 # needs an Android SDK
```

MIT licensed.
