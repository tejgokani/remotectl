use std::{
    fs,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{anyhow, Context, Result};
use rand::RngCore;
use remotectl_protocol::{b64_encode, noise};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedPhone {
    /// Phone's Noise static public key (base64url). This is the phone's identity.
    pub pubkey: String,
    pub paired_at: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub device_id: String,
    /// Friendly name shown on the phone. Defaults to the hostname.
    pub name: String,
    pub relay: String,
    /// Relay room secret (authorizes room access only; E2E keys are separate).
    pub secret: String,
    pub private_key: String,
    pub public_key: String,
    pub paired: Vec<PairedPhone>,
    /// Remote terminal is opt-in: off unless the laptop owner turns it on.
    pub terminal_enabled: bool,
}

pub fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

pub fn data_dir() -> Result<PathBuf> {
    let home = directories::BaseDirs::new().ok_or_else(|| anyhow!("no home directory"))?;
    let dir = home.home_dir().join(".remotectl");
    fs::create_dir_all(&dir)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700))?;
    }
    Ok(dir)
}

fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::thread_rng().fill_bytes(&mut buf);
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

/// Write a private file atomically with owner-only permissions.
fn write_private(path: &PathBuf, contents: &[u8]) -> Result<()> {
    let tmp = path.with_extension("tmp");
    #[cfg(unix)]
    {
        use std::{io::Write, os::unix::fs::OpenOptionsExt};
        let mut f = fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(&tmp)?;
        f.write_all(contents)?;
    }
    #[cfg(not(unix))]
    fs::write(&tmp, contents)?;
    fs::rename(&tmp, path)?;
    Ok(())
}

impl Config {
    fn path() -> Result<PathBuf> {
        Ok(data_dir()?.join("config.json"))
    }

    pub fn exists() -> bool {
        Self::path().map(|p| p.exists()).unwrap_or(false)
    }

    pub fn load() -> Result<Self> {
        let p = Self::path()?;
        let raw = fs::read_to_string(&p).with_context(|| format!("no config at {} — run `remotectl pair` first", p.display()))?;
        Ok(serde_json::from_str(&raw)?)
    }

    pub fn save(&self) -> Result<()> {
        write_private(&Self::path()?, &serde_json::to_vec_pretty(self)?)
    }

    pub fn create(relay: String) -> Result<Self> {
        let kp = noise::generate_keypair()?;
        let name = sysinfo::System::host_name().unwrap_or_else(|| "laptop".into());
        let cfg = Self {
            device_id: random_hex(8),
            name,
            relay,
            secret: random_hex(24),
            private_key: b64_encode(&kp.private),
            public_key: b64_encode(&kp.public),
            paired: Vec::new(),
            terminal_enabled: false,
        };
        cfg.save()?;
        Ok(cfg)
    }

    pub fn is_paired(&self, pubkey: &str) -> bool {
        self.paired.iter().any(|p| p.pubkey == pubkey)
    }
}

/// A pending one-time invite created by `remotectl pair` and consumed by the daemon.
#[derive(Debug, Serialize, Deserialize)]
struct Pending {
    code_hash: String,
    expires: u64,
    attempts: u8,
}

const MAX_ATTEMPTS: u8 = 5;
pub const INVITE_TTL_SECS: u64 = 300;

fn pending_path() -> Result<PathBuf> {
    Ok(data_dir()?.join("pending.json"))
}

fn hash_code(code: &str) -> String {
    Sha256::digest(code.trim().to_uppercase().as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}

pub fn new_pair_code() -> Result<String> {
    // Unambiguous alphabet, 10 chars ≈ 50 bits, plus attempt limiting on the verifier.
    const ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    let mut rng = rand::thread_rng();
    let code: String = (0..10)
        .map(|_| ALPHABET[(rng.next_u32() as usize) % ALPHABET.len()] as char)
        .collect();
    let pending = Pending { code_hash: hash_code(&code), expires: now() + INVITE_TTL_SECS, attempts: 0 };
    write_private(&pending_path()?, &serde_json::to_vec(&pending)?)?;
    Ok(code)
}

pub fn clear_pending() {
    if let Ok(p) = pending_path() {
        let _ = fs::remove_file(p);
    }
}

/// Verify and consume a pair code. Wrong guesses count toward a limit that burns the invite.
pub fn consume_pair_code(code: &str) -> bool {
    let Ok(path) = pending_path() else { return false };
    let Ok(raw) = fs::read_to_string(&path) else { return false };
    let Ok(mut p) = serde_json::from_str::<Pending>(&raw) else { return false };
    if now() > p.expires || p.attempts >= MAX_ATTEMPTS {
        let _ = fs::remove_file(&path);
        return false;
    }
    let given = hash_code(code);
    let ok = given.len() == p.code_hash.len()
        && given.bytes().zip(p.code_hash.bytes()).fold(0u8, |a, (x, y)| a | (x ^ y)) == 0;
    if ok {
        let _ = fs::remove_file(&path);
    } else {
        p.attempts += 1;
        if let Ok(b) = serde_json::to_vec(&p) {
            let _ = write_private(&path, &b);
        }
    }
    ok
}

pub fn pid_path() -> Result<PathBuf> {
    Ok(data_dir()?.join("agent.pid"))
}

pub fn log_path() -> Result<PathBuf> {
    Ok(data_dir()?.join("agent.log"))
}
