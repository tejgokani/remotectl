use std::{
    collections::HashMap,
    time::{Duration, Instant},
};

use anyhow::{anyhow, bail, Result};
use futures_util::{SinkExt, StreamExt};
use remotectl_protocol::{b64_decode, b64_encode, control, noise, Frame, Req, Res};
use snow::HandshakeState;
use tokio::{
    sync::mpsc::{self, UnboundedSender},
    time::interval,
};
use tokio_tungstenite::{connect_async, tungstenite::Message};
use tracing::{info, warn};

use crate::{apps, config::{self, Config, PairedPhone}, lock, stats::Collector, term::Term};

const MAX_TERMS: usize = 4;

/// Run forever: connect to the relay, serve one phone at a time, reconnect with backoff.
pub async fn run() -> Result<()> {
    let _pid = PidGuard::new();
    let mut backoff = Duration::from_secs(1);
    loop {
        // Reload each time so `remotectl config set ...` and new pairings are picked up.
        let cfg = Config::load()?;
        let started = Instant::now();
        let clean = match session(&cfg).await {
            Ok(()) => {
                info!("relay connection closed");
                true
            }
            Err(e) => {
                warn!("relay connection ended: {e:#}");
                false
            }
        };
        // A clean end, or a connection that stayed up a while, resets the backoff.
        backoff = if clean || started.elapsed() > Duration::from_secs(30) {
            Duration::from_secs(1)
        } else {
            (backoff * 2).min(Duration::from_secs(60))
        };
        tokio::time::sleep(backoff).await;
    }
}

struct PidGuard;
impl PidGuard {
    fn new() -> Self {
        if let Ok(p) = config::pid_path() {
            let _ = std::fs::write(p, std::process::id().to_string());
        }
        Self
    }
}
impl Drop for PidGuard {
    fn drop(&mut self) {
        if let Ok(p) = config::pid_path() {
            let _ = std::fs::remove_file(p);
        }
    }
}

enum Phase {
    Idle,
    Handshake(Box<HandshakeState>),
    Ready(Ready),
}

struct Ready {
    ch: noise::Channel,
    authed: bool,
    phone_key: String,
}

struct Ctx {
    cfg: Config,
    out: UnboundedSender<Frame<Res>>,
    collector: Collector,
    stats_every: Option<Duration>,
    terms: HashMap<u32, Term>,
}

impl Ctx {
    fn reset_session_state(&mut self) {
        self.terms.clear(); // Drop kills the shells
        self.stats_every = None;
    }
}

async fn session(cfg: &Config) -> Result<()> {
    let url = format!("{}/ws/agent/{}?secret={}", cfg.relay.trim_end_matches('/'), cfg.device_id, cfg.secret);
    let (ws, _) = connect_async(url).await?;
    info!("connected to relay {}", cfg.relay);
    let (mut sink, mut stream) = ws.split();

    let (out_tx, mut out_rx) = mpsc::unbounded_channel::<Frame<Res>>();
    let private = b64_decode(&cfg.private_key)?;
    let mut ctx = Ctx {
        cfg: cfg.clone(),
        out: out_tx,
        collector: Collector::new(),
        stats_every: None,
        terms: HashMap::new(),
    };
    let mut phase = Phase::Idle;

    let mut ping = interval(Duration::from_secs(20));
    let mut last_rx = Instant::now();
    let mut stats_tick = interval(Duration::from_secs(1));
    let mut stats_last = Instant::now();

    loop {
        tokio::select! {
            msg = stream.next() => {
                let Some(msg) = msg else { return Ok(()) };
                last_rx = Instant::now();
                match msg? {
                    Message::Text(t) if t.as_str() == control::PEER_JOINED => {
                        ctx.reset_session_state();
                        while out_rx.try_recv().is_ok() {}
                        phase = Phase::Handshake(Box::new(noise::responder(&private)?));
                        info!("phone connecting, awaiting handshake");
                    }
                    Message::Text(t) if t.as_str() == control::PEER_LEFT => {
                        ctx.reset_session_state();
                        phase = Phase::Idle;
                        info!("phone disconnected");
                    }
                    Message::Binary(data) => {
                        let (next, replies) = on_binary(phase, &data, &mut ctx);
                        phase = next;
                        for r in replies {
                            sink.send(Message::Binary(r.into())).await?;
                        }
                    }
                    Message::Close(_) => return Ok(()),
                    _ => {}
                }
            }
            Some(frame) = out_rx.recv() => {
                if let Phase::Ready(r) = &mut phase {
                    if r.authed {
                        for f in r.ch.seal(&serde_json::to_vec(&frame)?)? {
                            sink.send(Message::Binary(f.into())).await?;
                        }
                    }
                }
            }
            _ = stats_tick.tick() => {
                if let (Some(every), Phase::Ready(r)) = (ctx.stats_every, &phase) {
                    if r.authed && stats_last.elapsed() >= every {
                        stats_last = Instant::now();
                        let s = ctx.collector.snapshot();
                        let _ = ctx.out.send(Frame { id: 0, body: Res::Stats(s) });
                    }
                }
            }
            _ = ping.tick() => {
                if last_rx.elapsed() > Duration::from_secs(70) {
                    bail!("relay connection timed out");
                }
                sink.send(Message::Ping(Vec::new().into())).await?;
                // Revocation: an unpaired phone must lose an already-open session.
                if let Phase::Ready(r) = &phase {
                    if r.authed && Config::load().map(|c| !c.is_paired(&r.phone_key)).unwrap_or(false) {
                        info!("phone was unpaired; closing session");
                        return Ok(());
                    }
                }
            }
        }
    }
}

/// Process one binary frame. Returns the next phase and any frames to send back.
fn on_binary(phase: Phase, data: &[u8], ctx: &mut Ctx) -> (Phase, Vec<Vec<u8>>) {
    match phase {
        Phase::Idle => (Phase::Idle, vec![]),
        Phase::Handshake(mut hs) => {
            let mut buf = vec![0u8; 65535];
            let mut replies = Vec::new();
            if let Err(e) = hs.read_message(data, &mut buf) {
                warn!("handshake failed: {e}");
                return (Phase::Idle, replies);
            }
            if !hs.is_handshake_finished() {
                match hs.write_message(&[], &mut buf) {
                    Ok(n) => replies.push(buf[..n].to_vec()),
                    Err(e) => {
                        warn!("handshake failed: {e}");
                        return (Phase::Idle, replies);
                    }
                }
            }
            if hs.is_handshake_finished() {
                match noise::Channel::from_handshake(*hs) {
                    Ok(ch) => {
                        let phone_key = b64_encode(&ch.remote_static);
                        return (Phase::Ready(Ready { ch, authed: false, phone_key }), replies);
                    }
                    Err(e) => warn!("handshake failed: {e}"),
                }
                return (Phase::Idle, replies);
            }
            (Phase::Handshake(hs), replies)
        }
        Phase::Ready(mut r) => {
            let plain = match r.ch.open(data) {
                Ok(Some(p)) => p,
                Ok(None) => return (Phase::Ready(r), vec![]),
                Err(e) => {
                    warn!("decrypt failed, dropping session: {e}");
                    return (Phase::Idle, vec![]);
                }
            };
            let req: Frame<Req> = match serde_json::from_slice(&plain) {
                Ok(f) => f,
                Err(e) => {
                    warn!("bad request: {e}");
                    return (Phase::Ready(r), vec![]);
                }
            };

            if !r.authed {
                let mut replies = Vec::new();
                let ok = authenticate(&mut r, &req.body, ctx);
                let res = if ok {
                    Res::AuthOk {
                        hostname: ctx.cfg.name.clone(),
                        os: std::env::consts::OS.into(),
                        terminal_enabled: ctx.cfg.terminal_enabled,
                    }
                } else {
                    Res::AuthErr { reason: "not paired".into() }
                };
                if let Ok(bytes) = serde_json::to_vec(&Frame { id: req.id, body: res }) {
                    if let Ok(frames) = r.ch.seal(&bytes) {
                        replies = frames;
                    }
                }
                if ok {
                    r.authed = true;
                    return (Phase::Ready(r), replies);
                }
                return (Phase::Idle, replies);
            }

            handle(req, ctx);
            (Phase::Ready(r), vec![])
        }
    }
}

fn authenticate(r: &mut Ready, req: &Req, ctx: &mut Ctx) -> bool {
    let Req::Auth { pair_code } = req else { return false };
    // Always consult the file: the phone may have been unpaired since this session started.
    let Ok(mut cfg) = Config::load() else { return false };
    if cfg.is_paired(&r.phone_key) {
        ctx.cfg = cfg;
        return true;
    }
    if let Some(code) = pair_code {
        if config::consume_pair_code(code) {
            cfg.paired.push(PairedPhone { pubkey: r.phone_key.clone(), paired_at: config::now() });
            if cfg.save().is_ok() {
                info!("paired new phone");
                ctx.cfg = cfg;
                return true;
            }
        }
    }
    warn!("rejected unpaired phone");
    false
}

fn reply(ctx: &Ctx, id: u64, res: Res) {
    let _ = ctx.out.send(Frame { id, body: res });
}

fn done_or_err(ctx: &Ctx, id: u64, r: Result<()>) {
    reply(ctx, id, match r {
        Ok(()) => Res::Done,
        Err(e) => Res::Error { message: e.to_string() },
    });
}

fn handle(req: Frame<Req>, ctx: &mut Ctx) {
    let id = req.id;
    match req.body {
        Req::Auth { .. } => {}
        Req::Ping => reply(ctx, id, Res::Pong),
        Req::StatsStart { interval_ms } => {
            ctx.stats_every = Some(Duration::from_millis(interval_ms.clamp(500, 60_000)));
            let s = ctx.collector.snapshot();
            reply(ctx, id, Res::Stats(s));
        }
        Req::StatsStop => {
            ctx.stats_every = None;
            reply(ctx, id, Res::Done);
        }
        Req::Processes => {
            let procs = ctx.collector.top_processes(15);
            reply(ctx, id, Res::Processes { processes: procs });
        }
        Req::Apps => {
            let out = ctx.out.clone();
            tokio::task::spawn_blocking(move || {
                let _ = out.send(Frame { id, body: Res::Apps { apps: apps::list() } });
            });
        }
        Req::AppLaunch { app_id } => done_or_err(ctx, id, apps::launch(&app_id)),
        Req::AppQuit { app_id, force } => done_or_err(ctx, id, apps::quit(&app_id, force)),
        Req::Lock => done_or_err(ctx, id, lock::lock_screen()),
        Req::TermOpen { term_id, cols, rows } => {
            let r = (|| -> Result<()> {
                if !ctx.cfg.terminal_enabled {
                    return Err(anyhow!("terminal is disabled on this laptop (run: remotectl config set terminal on)"));
                }
                if ctx.terms.len() >= MAX_TERMS && !ctx.terms.contains_key(&term_id) {
                    return Err(anyhow!("too many terminals"));
                }
                let t = Term::open(term_id, cols, rows, ctx.out.clone())?;
                ctx.terms.insert(term_id, t);
                Ok(())
            })();
            done_or_err(ctx, id, r);
        }
        Req::TermInput { term_id, data } => {
            let r = (|| -> Result<()> {
                let t = ctx.terms.get_mut(&term_id).ok_or_else(|| anyhow!("no such terminal"))?;
                t.write(&b64_decode(&data)?)
            })();
            if let Err(e) = r {
                reply(ctx, id, Res::Error { message: e.to_string() });
            }
        }
        Req::TermResize { term_id, cols, rows } => {
            let r = ctx.terms.get(&term_id).ok_or_else(|| anyhow!("no such terminal")).and_then(|t| t.resize(cols, rows));
            if let Err(e) = r {
                reply(ctx, id, Res::Error { message: e.to_string() });
            }
        }
        Req::TermClose { term_id } => {
            ctx.terms.remove(&term_id);
            reply(ctx, id, Res::Done);
        }
    }
}
