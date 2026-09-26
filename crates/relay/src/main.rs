//! remotectl relay: rendezvous point between laptop agents and phones.
//!
//! The relay authorizes room access with a shared secret and forwards opaque binary
//! frames. All real content is Noise-encrypted end to end, so the relay cannot read or
//! forge commands. One room per laptop; a phone talks to many laptops by opening one
//! socket per room, and `POST /v1/presence` reports which of its laptops are online.

use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};

use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Path, Query, State,
    },
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::mpsc;

const PEER_JOINED: &str = r#"{"t":"peer_joined"}"#;
const PEER_LEFT: &str = r#"{"t":"peer_left"}"#;
const MAX_ROOMS: usize = 100_000;
const MAX_FRAME: usize = 1 << 20;
const IDLE_TIMEOUT: Duration = Duration::from_secs(90);

type Tx = mpsc::UnboundedSender<Message>;

struct Peer {
    conn: u64,
    tx: Tx,
}

struct Room {
    secret_hash: [u8; 32],
    agent: Option<Peer>,
    client: Option<Peer>,
}

#[derive(Default)]
struct Inner {
    rooms: Mutex<HashMap<String, Room>>,
    next_conn: AtomicU64,
}

type App = Arc<Inner>;

#[derive(Deserialize)]
struct WsQuery {
    secret: String,
}

fn hash(secret: &str) -> [u8; 32] {
    Sha256::digest(secret.as_bytes()).into()
}

fn ct_eq(a: &[u8; 32], b: &[u8; 32]) -> bool {
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn valid_id(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

#[derive(Clone, Copy, PartialEq)]
enum Role {
    Agent,
    Client,
}

async fn ws_handler(
    State(app): State<App>,
    Path((role, device_id)): Path<(String, String)>,
    Query(q): Query<WsQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    let role = match role.as_str() {
        "agent" => Role::Agent,
        "client" => Role::Client,
        _ => return StatusCode::NOT_FOUND.into_response(),
    };
    if !valid_id(&device_id) || q.secret.is_empty() || q.secret.len() > 256 {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let secret_hash = hash(&q.secret);
    let conn = app.next_conn.fetch_add(1, Ordering::Relaxed);
    let (tx, rx) = mpsc::unbounded_channel::<Message>();

    // Register the peer (or reject) before upgrading so failures return a real HTTP status.
    {
        let mut rooms = app.rooms.lock().unwrap();
        match (rooms.get_mut(&device_id), role) {
            (Some(room), _) if !ct_eq(&room.secret_hash, &secret_hash) => {
                return StatusCode::FORBIDDEN.into_response();
            }
            (Some(room), Role::Agent) => room.agent = Some(Peer { conn, tx: tx.clone() }),
            (Some(room), Role::Client) => room.client = Some(Peer { conn, tx: tx.clone() }),
            (None, Role::Agent) => {
                if rooms.len() >= MAX_ROOMS {
                    return StatusCode::SERVICE_UNAVAILABLE.into_response();
                }
                rooms.insert(
                    device_id.clone(),
                    Room { secret_hash, agent: Some(Peer { conn, tx: tx.clone() }), client: None },
                );
            }
            // A phone can't create rooms: only an agent that registered the device can.
            (None, Role::Client) => return StatusCode::NOT_FOUND.into_response(),
        }
        let room = rooms.get(&device_id).unwrap();
        if let (Some(a), Some(c)) = (&room.agent, &room.client) {
            let _ = a.tx.send(Message::Text(PEER_JOINED.into()));
            let _ = c.tx.send(Message::Text(PEER_JOINED.into()));
        }
    }

    ws.max_message_size(MAX_FRAME)
        .on_upgrade(move |socket| run_socket(app, socket, device_id, role, conn, rx))
}

async fn run_socket(
    app: App,
    socket: WebSocket,
    device_id: String,
    role: Role,
    conn: u64,
    mut rx: mpsc::UnboundedReceiver<Message>,
) {
    let (mut sink, mut stream) = socket.split();

    let writer = tokio::spawn(async move {
        let mut ping = tokio::time::interval(Duration::from_secs(30));
        loop {
            tokio::select! {
                msg = rx.recv() => match msg {
                    Some(m) => if sink.send(m).await.is_err() { break },
                    None => break, // replaced by a newer connection
                },
                _ = ping.tick() => if sink.send(Message::Ping(Default::default())).await.is_err() { break },
            }
        }
        let _ = sink.close().await;
    });

    loop {
        let next = tokio::time::timeout(IDLE_TIMEOUT, stream.next()).await;
        let msg = match next {
            Ok(Some(Ok(m))) => m,
            _ => break,
        };
        match msg {
            // Only binary frames are forwarded. Peers must not be able to inject
            // relay control messages (text frames), so those are dropped.
            Message::Binary(data) => {
                let rooms = app.rooms.lock().unwrap();
                if let Some(room) = rooms.get(&device_id) {
                    let peer = if role == Role::Agent { &room.client } else { &room.agent };
                    if let Some(p) = peer {
                        let _ = p.tx.send(Message::Binary(data));
                    }
                }
            }
            Message::Close(_) => break,
            _ => {}
        }
    }

    // Clear our slot only if a newer connection hasn't replaced us.
    {
        let mut rooms = app.rooms.lock().unwrap();
        if let Some(room) = rooms.get_mut(&device_id) {
            let (mine, other) = match role {
                Role::Agent => (&mut room.agent, &room.client),
                Role::Client => (&mut room.client, &room.agent),
            };
            if mine.as_ref().is_some_and(|p| p.conn == conn) {
                *mine = None;
                if let Some(o) = other {
                    let _ = o.tx.send(Message::Text(PEER_LEFT.into()));
                }
            }
        }
    }
    writer.abort();
}

#[derive(Deserialize)]
struct PresenceQuery {
    device_id: String,
    secret: String,
}

#[derive(Serialize)]
struct PresenceEntry {
    device_id: String,
    online: bool,
}

/// Phone dashboard support: which of my laptops are online right now?
async fn presence(State(app): State<App>, Json(devices): Json<Vec<PresenceQuery>>) -> Response {
    if devices.len() > 200 {
        return StatusCode::PAYLOAD_TOO_LARGE.into_response();
    }
    let rooms = app.rooms.lock().unwrap();
    let out: Vec<PresenceEntry> = devices
        .into_iter()
        .map(|d| {
            let online = rooms
                .get(&d.device_id)
                .is_some_and(|r| ct_eq(&r.secret_hash, &hash(&d.secret)) && r.agent.is_some());
            PresenceEntry { device_id: d.device_id, online }
        })
        .collect();
    Json(out).into_response()
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_env_filter("info").init();
    let addr: SocketAddr = std::env::args()
        .nth(1)
        .or_else(|| std::env::var("LISTEN").ok())
        .unwrap_or_else(|| {
            format!("0.0.0.0:{}", std::env::var("PORT").unwrap_or_else(|_| "8787".into()))
        })
        .parse()
        .expect("listen address like 0.0.0.0:8787");

    let app = Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/v1/presence", post(presence))
        .route("/ws/{role}/{device_id}", get(ws_handler))
        .with_state(Arc::new(Inner::default()));

    tracing::info!("remotectl-relay listening on {addr}");
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    axum::serve(listener, app).await.expect("serve");
}
