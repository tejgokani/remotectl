//! Wire protocol shared by the laptop agent (`remotectl`), the relay and phone clients.
//!
//! Layers:
//!   1. WebSocket (relay-routed, the relay sees only opaque binary frames)
//!   2. Noise_XX_25519_ChaChaPoly_BLAKE2s, end-to-end between phone and agent
//!   3. JSON `Frame`s carrying `Req` / `Res` bodies, fragmented to fit Noise's 64 KiB limit

pub mod noise;

use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64URL, Engine};
use serde::{Deserialize, Serialize};

/// Everything a phone needs to pair with an agent. Encoded into the pairing QR code.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Invite {
    pub relay: String,
    /// 16 hex chars (8 bytes).
    pub device_id: String,
    /// 48 hex chars (24 bytes). Authorizes relay room access only; not the E2E key.
    pub secret: String,
    /// Agent's Noise static public key (base64url, 32 bytes). The phone pins this.
    pub agent_pub: String,
    /// One-time code proving the phone saw the QR; consumed on first successful pair.
    pub code: String,
}

const INVITE_PREFIX: &str = "remotectl://p/";
const INVITE_VERSION: u8 = 2;
const CODE_LEN: usize = 10;

fn unhex(s: &str, len: usize) -> anyhow::Result<Vec<u8>> {
    if s.len() != len * 2 || !s.is_ascii() {
        anyhow::bail!("expected {} hex chars", len * 2);
    }
    (0..len)
        .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).map_err(Into::into))
        .collect()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

impl Invite {
    /// Compact binary layout, then base64url, to keep the QR code small:
    /// `[ver:1][device_id:8][secret:24][agent_pub:32][code:10][relay: rest, UTF-8]`
    pub fn encode(&self) -> anyhow::Result<String> {
        if self.code.len() != CODE_LEN || !self.code.is_ascii() {
            anyhow::bail!("pair code must be {CODE_LEN} ASCII chars");
        }
        let mut b = vec![INVITE_VERSION];
        b.extend(unhex(&self.device_id, 8)?);
        b.extend(unhex(&self.secret, 24)?);
        b.extend(B64URL.decode(&self.agent_pub)?);
        b.extend(self.code.as_bytes());
        b.extend(self.relay.as_bytes());
        Ok(format!("{INVITE_PREFIX}{}", B64URL.encode(b)))
    }

    pub fn decode(s: &str) -> anyhow::Result<Self> {
        let body = s
            .trim()
            .strip_prefix(INVITE_PREFIX)
            .ok_or_else(|| anyhow::anyhow!("not a remotectl invite"))?;
        let b = B64URL.decode(body)?;
        const FIXED: usize = 1 + 8 + 24 + 32 + CODE_LEN;
        if b.len() <= FIXED || b[0] != INVITE_VERSION {
            anyhow::bail!("unsupported or truncated invite");
        }
        Ok(Self {
            device_id: hex(&b[1..9]),
            secret: hex(&b[9..33]),
            agent_pub: B64URL.encode(&b[33..65]),
            code: String::from_utf8(b[65..65 + CODE_LEN].to_vec())?,
            relay: String::from_utf8(b[FIXED..].to_vec())?,
        })
    }
}

pub fn b64_encode(bytes: &[u8]) -> String {
    B64URL.encode(bytes)
}

pub fn b64_decode(s: &str) -> anyhow::Result<Vec<u8>> {
    Ok(B64URL.decode(s)?)
}

/// Control messages the relay sends as WebSocket *text* frames.
pub mod control {
    pub const PEER_JOINED: &str = r#"{"t":"peer_joined"}"#;
    pub const PEER_LEFT: &str = r#"{"t":"peer_left"}"#;
}

/// Envelope. `id` correlates a response to its request; unsolicited pushes use 0.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Frame<T> {
    pub id: u64,
    #[serde(flatten)]
    pub body: T,
}

/// Phone -> agent.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Req {
    /// Must be the first message. `pair_code` is only needed on first pairing.
    Auth { pair_code: Option<String> },
    Ping,
    StatsStart { interval_ms: u64 },
    StatsStop,
    Processes,
    Apps,
    AppLaunch { app_id: String },
    AppQuit { app_id: String, force: bool },
    Lock,
    TermOpen { term_id: u32, cols: u16, rows: u16 },
    TermInput { term_id: u32, data: String },
    TermResize { term_id: u32, cols: u16, rows: u16 },
    TermClose { term_id: u32 },
}

/// Agent -> phone.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Res {
    AuthOk { hostname: String, os: String, terminal_enabled: bool },
    AuthErr { reason: String },
    Pong,
    Done,
    Error { message: String },
    Stats(Stats),
    Processes { processes: Vec<ProcessInfo> },
    Apps { apps: Vec<AppInfo> },
    /// `data` is base64 (standard URL-safe, no padding) of raw PTY bytes.
    TermData { term_id: u32, data: String },
    TermExit { term_id: u32, code: Option<i32> },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Stats {
    pub hostname: String,
    pub os: String,
    pub uptime_secs: u64,
    pub cpu_percent: f32,
    pub cpu_cores: Vec<f32>,
    pub load_avg: [f64; 3],
    pub mem_total: u64,
    pub mem_used: u64,
    pub swap_total: u64,
    pub swap_used: u64,
    pub disks: Vec<DiskInfo>,
    pub net_rx_bytes: u64,
    pub net_tx_bytes: u64,
    pub battery: Option<Battery>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiskInfo {
    pub mount: String,
    pub total: u64,
    pub available: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Battery {
    pub percent: u8,
    pub charging: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessInfo {
    pub pid: u32,
    pub name: String,
    pub cpu_percent: f32,
    pub mem_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppInfo {
    /// Stable identifier: bundle id on macOS, AppUserModelID on Windows.
    pub id: String,
    pub name: String,
    pub running: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invite_roundtrip_is_compact() {
        let inv = Invite {
            relay: "wss://remotectl-relay.example.workers.dev".into(),
            device_id: "0123456789abcdef".into(),
            secret: "00112233445566778899aabbccddeeff0011223344556677".into(),
            agent_pub: B64URL.encode([7u8; 32]),
            code: "ABCDEFGH23".into(),
        };
        let text = inv.encode().unwrap();
        assert!(text.len() < 190, "invite is {} chars", text.len());
        assert_eq!(Invite::decode(&text).unwrap(), inv);
    }

    #[test]
    fn invite_rejects_garbage() {
        assert!(Invite::decode("remotectl://p/AAAA").is_err());
        assert!(Invite::decode("https://example.com").is_err());
    }
}
