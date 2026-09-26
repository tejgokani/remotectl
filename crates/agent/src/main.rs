mod apps;
mod config;
mod daemon;
mod install;
mod lock;
mod stats;
mod term;

use std::time::Duration;

use anyhow::{anyhow, bail, Result};
use clap::{Parser, Subcommand};
use remotectl_protocol::Invite;

use config::Config;

#[derive(Parser)]
#[command(name = "remotectl", version, about = "Control this computer from your phone")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Show a QR code to pair a phone with this computer
    Pair {
        /// Relay server URL, e.g. wss://relay.example.com (saved after first use)
        #[arg(long, env = "REMOTECTL_RELAY")]
        relay: Option<String>,
    },
    /// Run the agent in the foreground (what the auto-start service runs)
    Run,
    /// Start the agent automatically at login, and start it now
    Install,
    /// Remove auto-start
    Uninstall,
    /// Show pairing and daemon status
    Status,
    /// List paired phones
    Phones,
    /// Revoke a phone (by number from `phones`), or all of them
    Unpair {
        index: Option<usize>,
        #[arg(long)]
        all: bool,
    },
    /// Change settings: `config terminal on`, `config name <text>`, `config relay <url>`
    /// (`config set terminal on` also works, and `config terminal` prints the current value)
    Config {
        #[arg(num_args = 1..=3, value_name = "[set] KEY [VALUE]")]
        args: Vec<String>,
    },
}

fn daemon_running() -> Option<u32> {
    let pid: u32 = std::fs::read_to_string(config::pid_path().ok()?).ok()?.trim().parse().ok()?;
    #[cfg(unix)]
    {
        (unsafe { libc::kill(pid as i32, 0) } == 0).then_some(pid)
    }
    #[cfg(not(unix))]
    {
        Some(pid)
    }
}

fn short(key: &str) -> String {
    key.chars().take(10).collect()
}

fn main() -> Result<()> {
    // rustls needs an explicit crypto provider when several are compiled in; without this every
    // wss:// connection panics. (Local ws:// testing never exercises TLS, so it hides this.)
    let _ = rustls::crypto::ring::default_provider().install_default();
    let cli = Cli::parse();
    // The daemon logs to stdout/stderr (captured by launchd); CLI subcommands stay quiet.
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build()?;

    match cli.cmd {
        Cmd::Run => {
            tracing_subscriber::fmt()
                .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
                .init();
            rt.block_on(daemon::run())
        }
        Cmd::Pair { relay } => pair(relay),
        Cmd::Install => {
            if !Config::exists() {
                bail!("not set up yet: run `remotectl pair --relay <url>` first");
            }
            install::install()
        }
        Cmd::Uninstall => install::uninstall(),
        Cmd::Status => {
            if !Config::exists() {
                println!("not set up. Run: remotectl pair --relay <url>");
                return Ok(());
            }
            let c = Config::load()?;
            println!("name        {}", c.name);
            println!("device id   {}", c.device_id);
            println!("relay       {}", c.relay);
            println!("terminal    {}", if c.terminal_enabled { "enabled" } else { "disabled" });
            println!("phones      {} paired", c.paired.len());
            match daemon_running() {
                Some(pid) => println!("agent       running (pid {pid})"),
                None => println!("agent       NOT running (start with: remotectl install)"),
            }
            Ok(())
        }
        Cmd::Phones => {
            let c = Config::load()?;
            if c.paired.is_empty() {
                println!("no phones paired");
            }
            for (i, p) in c.paired.iter().enumerate() {
                println!("{}  key {}…  paired {}s ago", i + 1, short(&p.pubkey), config::now().saturating_sub(p.paired_at));
            }
            Ok(())
        }
        Cmd::Unpair { index, all } => {
            let mut c = Config::load()?;
            if all {
                c.paired.clear();
            } else {
                let i = index.ok_or_else(|| anyhow!("give a phone number from `remotectl phones`, or --all"))?;
                if i == 0 || i > c.paired.len() {
                    bail!("no such phone");
                }
                c.paired.remove(i - 1);
            }
            c.save()?;
            println!("revoked; open sessions from that phone close within ~20s");
            Ok(())
        }
        Cmd::Config { args } => {
            let mut args = args.into_iter();
            let mut key = args.next().unwrap_or_default();
            // Accept the natural `config set <key> <value>` spelling as well as `config <key> <value>`.
            if key == "set" || key == "get" {
                key = args.next().ok_or_else(|| anyhow!("which setting? name, relay, or terminal"))?;
            }
            let value = args.next();
            let mut c = Config::load()?;
            match (key.as_str(), value) {
                ("terminal", Some(v)) => c.terminal_enabled = matches!(v.as_str(), "on" | "true" | "1" | "yes"),
                ("name", Some(v)) => c.name = v,
                ("relay", Some(v)) => c.relay = v,
                (k @ ("terminal" | "name" | "relay"), None) => {
                    let v = match k {
                        "terminal" => if c.terminal_enabled { "on".to_string() } else { "off".to_string() },
                        "name" => c.name.clone(),
                        _ => c.relay.clone(),
                    };
                    println!("{v}");
                    return Ok(());
                }
                _ => bail!("unknown setting; use: terminal on|off, name <text>, relay <url>"),
            }
            c.save()?;
            println!("saved. Name and terminal apply the next time a phone connects; a relay change needs `remotectl install` to restart the agent.");
            Ok(())
        }
    }
}

fn pair(relay: Option<String>) -> Result<()> {
    let mut cfg = if Config::exists() {
        let mut c = Config::load()?;
        if let Some(r) = relay {
            if c.relay != r {
                c.relay = r;
                c.save()?;
            }
        }
        c
    } else {
        let r = relay.ok_or_else(|| anyhow!("first-time setup needs a relay: remotectl pair --relay wss://your-relay"))?;
        Config::create(r)?
    };
    if cfg.relay.is_empty() {
        cfg.relay = relay_required()?;
    }

    let code = config::new_pair_code()?;
    let invite = Invite {
        relay: cfg.relay.clone(),
        device_id: cfg.device_id.clone(),
        secret: cfg.secret.clone(),
        agent_pub: cfg.public_key.clone(),
        code,
    };
    let text = invite.encode()?;

    println!("Scan this with the remotectl phone app  (valid for {} min):\n", config::INVITE_TTL_SECS / 60);
    let qr = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::L)?;
    println!("{}", qr.render::<qrcode::render::unicode::Dense1x2>().quiet_zone(true).build());
    println!("\nOr paste this into the app:\n{text}\n");

    if daemon_running().is_none() {
        println!("Note: the agent isn't running yet. Start it in another terminal with `remotectl run`,");
        println!("or run `remotectl install` to start it now and at every login.\n");
    }

    let before = cfg.paired.len();
    let deadline = std::time::Instant::now() + Duration::from_secs(config::INVITE_TTL_SECS);
    println!("Waiting for phone… (Ctrl-C to cancel)");
    while std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_secs(1));
        if Config::load().map(|c| c.paired.len() > before).unwrap_or(false) {
            println!("✔ Phone paired.");
            return Ok(());
        }
    }
    config::clear_pending();
    bail!("timed out; run `remotectl pair` again")
}

fn relay_required() -> Result<String> {
    Err(anyhow!("no relay configured: pass --relay <url>"))
}
