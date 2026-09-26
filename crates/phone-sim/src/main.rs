//! remotectl-sim: a command-line "phone" for testing the agent and relay end to end.
//! It behaves like the real phone app: one identity key, a book of many paired laptops,
//! each laptop's public key pinned at pairing time.

use std::{path::PathBuf, time::Duration};

use anyhow::{anyhow, bail, Context, Result};
use clap::{Parser, Subcommand};
use futures_util::{SinkExt, StreamExt};
use remotectl_protocol::{b64_decode, b64_encode, control, noise, Frame, Invite, Req, Res};
use serde::{Deserialize, Serialize};
use tokio::time::timeout;
use tokio_tungstenite::{connect_async, tungstenite::Message};

#[derive(Serialize, Deserialize, Clone)]
struct Device {
    name: String,
    device_id: String,
    relay: String,
    secret: String,
    agent_pub: String,
}

#[derive(Serialize, Deserialize)]
struct Store {
    private_key: String,
    public_key: String,
    devices: Vec<Device>,
}

fn store_path() -> Result<PathBuf> {
    let base = directories::BaseDirs::new().ok_or_else(|| anyhow!("no home"))?;
    let dir = std::env::var("REMOTECTL_SIM_HOME").map(PathBuf::from).unwrap_or_else(|_| base.home_dir().join(".remotectl-sim"));
    std::fs::create_dir_all(&dir)?;
    Ok(dir.join("phone.json"))
}

fn load_store() -> Result<Store> {
    let p = store_path()?;
    if let Ok(raw) = std::fs::read_to_string(&p) {
        return Ok(serde_json::from_str(&raw)?);
    }
    let kp = noise::generate_keypair()?;
    let s = Store { private_key: b64_encode(&kp.private), public_key: b64_encode(&kp.public), devices: vec![] };
    save_store(&s)?;
    Ok(s)
}

fn save_store(s: &Store) -> Result<()> {
    std::fs::write(store_path()?, serde_json::to_vec_pretty(s)?)?;
    Ok(())
}

#[derive(Parser)]
#[command(name = "remotectl-sim", about = "Simulated phone for testing remotectl")]
struct Cli {
    /// Which laptop to talk to (name or device id). Optional when only one is paired.
    #[arg(long, short, global = true)]
    device: Option<String>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Pair with a laptop using the invite text shown by `remotectl pair`
    Pair { invite: String },
    /// List paired laptops
    Devices,
    /// Forget a laptop on this phone
    Forget { name: String },
    /// Print CPU/memory/disk/network/battery (use --watch N to stream N updates)
    Stats {
        #[arg(long)]
        watch: Option<u32>,
    },
    /// Top processes
    Procs,
    /// List installed apps
    Apps,
    /// Launch an app by id
    Launch { app_id: String },
    /// Quit an app by id
    Quit {
        app_id: String,
        #[arg(long)]
        force: bool,
    },
    /// Lock the screen
    Lock,
    /// Run one shell command in a remote terminal and print its output
    Term { command: String },
    /// Send a ping and print the round-trip time
    Ping,
}

struct Conn {
    sink: futures_util::stream::SplitSink<tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>, Message>,
    stream: futures_util::stream::SplitStream<tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>>,
    ch: noise::Channel,
    next_id: u64,
}

impl Conn {
    /// Connect through the relay, run the Noise handshake, verify the laptop's pinned key.
    async fn open(dev: &Device, private: &[u8]) -> Result<Self> {
        let url = format!("{}/ws/client/{}?secret={}", dev.relay.trim_end_matches('/'), dev.device_id, dev.secret);
        let (ws, _) = connect_async(url).await.context("relay refused the connection (unknown device or bad secret?)")?;
        let (mut sink, mut stream) = ws.split();

        // Wait until the laptop is online in the room.
        timeout(Duration::from_secs(10), async {
            loop {
                match stream.next().await {
                    Some(Ok(Message::Text(t))) if t.as_str() == control::PEER_JOINED => return Ok(()),
                    Some(Ok(_)) => {}
                    _ => return Err(anyhow!("relay closed")),
                }
            }
        })
        .await
        .map_err(|_| anyhow!("'{}' is offline", dev.name))??;

        let mut hs = noise::initiator(private)?;
        let mut buf = vec![0u8; 65535];
        let n = hs.write_message(&[], &mut buf)?;
        sink.send(Message::Binary(buf[..n].to_vec().into())).await?;
        let msg2 = next_binary(&mut stream).await?;
        hs.read_message(&msg2, &mut buf)?;
        let n = hs.write_message(&[], &mut buf)?;
        sink.send(Message::Binary(buf[..n].to_vec().into())).await?;

        let ch = noise::Channel::from_handshake(hs)?;
        if b64_encode(&ch.remote_static) != dev.agent_pub {
            bail!("laptop key does not match the one pinned at pairing — refusing (possible impersonation)");
        }
        Ok(Self { sink, stream, ch, next_id: 1 })
    }

    async fn send(&mut self, body: Req) -> Result<u64> {
        let id = self.next_id;
        self.next_id += 1;
        for f in self.ch.seal(&serde_json::to_vec(&Frame { id, body })?)? {
            self.sink.send(Message::Binary(f.into())).await?;
        }
        Ok(id)
    }

    async fn recv(&mut self) -> Result<Frame<Res>> {
        loop {
            match self.stream.next().await.ok_or_else(|| anyhow!("connection closed"))?? {
                Message::Binary(b) => {
                    if let Some(plain) = self.ch.open(&b)? {
                        return Ok(serde_json::from_slice(&plain)?);
                    }
                }
                Message::Text(t) if t.as_str() == control::PEER_LEFT => bail!("laptop disconnected"),
                Message::Close(_) => bail!("connection closed"),
                _ => {}
            }
        }
    }

    /// Send a request and return the response with the matching id, ignoring pushes.
    async fn call(&mut self, body: Req) -> Result<Res> {
        let id = self.send(body).await?;
        timeout(Duration::from_secs(20), async {
            loop {
                let f = self.recv().await?;
                if f.id == id {
                    return Ok(f.body);
                }
            }
        })
        .await
        .map_err(|_| anyhow!("timed out"))?
    }
}

async fn next_binary<S>(stream: &mut S) -> Result<Vec<u8>>
where
    S: futures_util::Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    loop {
        match stream.next().await.ok_or_else(|| anyhow!("closed during handshake"))?? {
            Message::Binary(b) => return Ok(b.to_vec()),
            Message::Close(_) => bail!("closed during handshake"),
            _ => {}
        }
    }
}

fn pick<'a>(store: &'a Store, want: &Option<String>) -> Result<&'a Device> {
    match want {
        Some(w) => {
            let w = w.to_lowercase();
            let hits: Vec<&Device> = store
                .devices
                .iter()
                .filter(|d| d.name.to_lowercase().contains(&w) || d.device_id.starts_with(&w))
                .collect();
            match hits.as_slice() {
                [one] => Ok(one),
                [] => bail!("no paired laptop matches '{w}' (see `devices`)"),
                _ => bail!("'{w}' matches several laptops; be more specific"),
            }
        }
        None => match store.devices.as_slice() {
            [one] => Ok(one),
            [] => bail!("no laptops paired yet: `remotectl-sim pair <invite>`"),
            _ => bail!("several laptops paired; choose one with --device <name>"),
        },
    }
}

fn gb(b: u64) -> String {
    format!("{:.1} GB", b as f64 / 1e9)
}

#[tokio::main]
async fn main() -> Result<()> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let cli = Cli::parse();
    let mut store = load_store()?;
    let private = b64_decode(&store.private_key)?;

    match cli.cmd {
        Cmd::Devices => {
            if store.devices.is_empty() {
                println!("no laptops paired");
            }
            for d in &store.devices {
                println!("{:<24} {}  via {}", d.name, &d.device_id[..8.min(d.device_id.len())], d.relay);
            }
            return Ok(());
        }
        Cmd::Forget { name } => {
            let before = store.devices.len();
            store.devices.retain(|d| !d.name.eq_ignore_ascii_case(&name));
            save_store(&store)?;
            println!("forgot {} laptop(s) (also run `remotectl unpair` on it to revoke this phone)", before - store.devices.len());
            return Ok(());
        }
        Cmd::Pair { invite } => {
            let inv = Invite::decode(&invite)?;
            let mut dev = Device {
                name: "pending".into(),
                device_id: inv.device_id.clone(),
                relay: inv.relay.clone(),
                secret: inv.secret.clone(),
                agent_pub: inv.agent_pub.clone(),
            };
            let mut conn = Conn::open(&dev, &private).await?;
            match conn.call(Req::Auth { pair_code: Some(inv.code) }).await? {
                Res::AuthOk { hostname, os, .. } => {
                    dev.name = hostname;
                    println!("paired with {} ({os})", dev.name);
                    store.devices.retain(|d| d.device_id != dev.device_id);
                    store.devices.push(dev);
                    save_store(&store)?;
                }
                Res::AuthErr { reason } => bail!("pairing rejected: {reason}"),
                other => bail!("unexpected reply: {other:?}"),
            }
            return Ok(());
        }
        _ => {}
    }

    let dev = pick(&store, &cli.device)?.clone();
    let mut conn = Conn::open(&dev, &private).await?;
    match conn.call(Req::Auth { pair_code: None }).await? {
        Res::AuthOk { .. } => {}
        Res::AuthErr { reason } => bail!("laptop rejected this phone: {reason}"),
        other => bail!("unexpected reply: {other:?}"),
    }

    match cli.cmd {
        Cmd::Ping => {
            let t = std::time::Instant::now();
            conn.call(Req::Ping).await?;
            println!("pong from {} in {:?}", dev.name, t.elapsed());
        }
        Cmd::Stats { watch } => {
            let id = conn.send(Req::StatsStart { interval_ms: 1000 }).await?;
            let mut shown = 0;
            let limit = watch.unwrap_or(1);
            while shown < limit {
                let f = conn.recv().await?;
                if let Res::Stats(s) = f.body {
                    if f.id != id && f.id != 0 {
                        continue;
                    }
                    shown += 1;
                    println!("{} — {}", s.hostname, s.os);
                    println!("  cpu      {:.1}%  ({} cores)  load {:.2} {:.2} {:.2}", s.cpu_percent, s.cpu_cores.len(), s.load_avg[0], s.load_avg[1], s.load_avg[2]);
                    println!("  memory   {} / {}   swap {} / {}", gb(s.mem_used), gb(s.mem_total), gb(s.swap_used), gb(s.swap_total));
                    for d in &s.disks {
                        println!("  disk     {:<22} {} free of {}", d.mount, gb(d.available), gb(d.total));
                    }
                    println!("  network  rx {} tx {}", gb(s.net_rx_bytes), gb(s.net_tx_bytes));
                    match &s.battery {
                        Some(b) => println!("  battery  {}% {}", b.percent, if b.charging { "(charging/AC)" } else { "(on battery)" }),
                        None => println!("  battery  none"),
                    }
                    println!("  uptime   {}h {}m", s.uptime_secs / 3600, (s.uptime_secs % 3600) / 60);
                }
            }
            conn.call(Req::StatsStop).await.ok();
        }
        Cmd::Procs => match conn.call(Req::Processes).await? {
            Res::Processes { processes } => {
                for p in processes {
                    println!("{:>7}  {:>5.1}%  {:>9}  {}", p.pid, p.cpu_percent, format!("{:.0} MB", p.mem_bytes as f64 / 1e6), p.name);
                }
            }
            other => bail!("unexpected reply: {other:?}"),
        },
        Cmd::Apps => match conn.call(Req::Apps).await? {
            Res::Apps { apps } => {
                println!("{} apps", apps.len());
                for a in apps {
                    println!("{} {:<34} {}", if a.running { "●" } else { " " }, a.name, a.id);
                }
            }
            other => bail!("unexpected reply: {other:?}"),
        },
        Cmd::Launch { app_id } => print_done(conn.call(Req::AppLaunch { app_id }).await?)?,
        Cmd::Quit { app_id, force } => print_done(conn.call(Req::AppQuit { app_id, force }).await?)?,
        Cmd::Lock => print_done(conn.call(Req::Lock).await?)?,
        Cmd::Term { command } => {
            match conn.call(Req::TermOpen { term_id: 1, cols: 100, rows: 30 }).await? {
                Res::Done => {}
                Res::Error { message } => bail!("{message}"),
                other => bail!("unexpected reply: {other:?}"),
            }
            let line = format!("{command}\nexit\n");
            conn.send(Req::TermInput { term_id: 1, data: b64_encode(line.as_bytes()) }).await?;
            let mut out = Vec::new();
            let _ = timeout(Duration::from_secs(8), async {
                loop {
                    match conn.recv().await {
                        Ok(Frame { body: Res::TermData { data, .. }, .. }) => out.extend(b64_decode(&data).unwrap_or_default()),
                        Ok(Frame { body: Res::TermExit { .. }, .. }) => break,
                        Ok(_) => {}
                        Err(_) => break,
                    }
                }
            })
            .await;
            print!("{}", String::from_utf8_lossy(&out));
        }
        Cmd::Pair { .. } | Cmd::Devices | Cmd::Forget { .. } => unreachable!(),
    }
    Ok(())
}

fn print_done(r: Res) -> Result<()> {
    match r {
        Res::Done => println!("ok"),
        Res::Error { message } => bail!("{message}"),
        other => bail!("unexpected reply: {other:?}"),
    }
    Ok(())
}
