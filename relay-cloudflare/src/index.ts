// remotectl relay on Cloudflare: one Durable Object ("Room") per laptop.
//
// The relay authorizes room access with a shared secret and forwards opaque binary frames
// between the laptop agent and the phone. Payloads are Noise-encrypted end to end, so the
// relay can neither read nor forge commands. A phone controls many laptops by opening one
// socket per room; POST /v1/presence tells it which of its laptops are online.

import { DurableObject } from "cloudflare:workers";

export interface Env {
  ROOMS: DurableObjectNamespace<Room>;
}

const PEER_JOINED = '{"t":"peer_joined"}';
const PEER_LEFT = '{"t":"peer_left"}';
const MAX_FRAME = 1 << 20;
const MAX_PRESENCE = 40; // keep within the subrequest limit on the free plan

type Role = "agent" | "client";

const validId = (s: string) => /^[A-Za-z0-9_-]{1,64}$/.test(s);
const validSecret = (s: string | null): s is string => !!s && s.length > 0 && s.length <= 256;

async function sha256Hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function constantTimeEq(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

const isOpen = (ws: WebSocket) => ws.readyState === WebSocket.READY_STATE_OPEN;

export class Room extends DurableObject<Env> {
  private open(role: Role, except?: WebSocket): WebSocket[] {
    return this.ctx.getWebSockets(role).filter((w) => w !== except && isOpen(w));
  }

  /** Presence check for the phone dashboard: is the laptop's agent connected right now? */
  async isOnline(secret: string): Promise<boolean> {
    const stored = await this.ctx.storage.get<string>("secretHash");
    if (!stored || !constantTimeEq(stored, await sha256Hex(secret))) return false;
    return this.open("agent").length > 0;
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    const url = new URL(request.url);
    const role = url.searchParams.get("role") as Role;
    const secret = url.searchParams.get("secret");
    if ((role !== "agent" && role !== "client") || !validSecret(secret)) {
      return new Response("bad request", { status: 400 });
    }

    const hash = await sha256Hex(secret);
    const stored = await this.ctx.storage.get<string>("secretHash");
    if (stored) {
      if (!constantTimeEq(stored, hash)) return new Response("forbidden", { status: 403 });
    } else if (role === "agent") {
      // First agent to show up registers the room. Persisted so it survives eviction.
      await this.ctx.storage.put("secretHash", hash);
    } else {
      // A phone can't create rooms; only an agent that registered the device can.
      return new Response("unknown device", { status: 404 });
    }

    // Newest connection of a role wins. Closing the old one is handled in webSocketClose,
    // which sees the replacement and stays quiet.
    const replaced = this.ctx.getWebSockets(role);

    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], [role]);
    for (const old of replaced) {
      try {
        old.close(1000, "replaced");
      } catch {}
    }

    const other: Role = role === "agent" ? "client" : "agent";
    const peers = this.open(other);
    if (peers.length > 0) {
      for (const p of peers) p.send(PEER_JOINED);
      pair[1].send(PEER_JOINED);
    }
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): void {
    // Only binary frames are forwarded. Peers must not be able to inject relay control
    // messages (text frames), so those are dropped.
    if (typeof message === "string" || message.byteLength > MAX_FRAME) return;
    const role = this.ctx.getTags(ws)[0] as Role | undefined;
    if (!role) return;
    const other: Role = role === "agent" ? "client" : "agent";
    for (const p of this.open(other)) p.send(message);
  }

  webSocketClose(ws: WebSocket, code: number): void {
    try {
      ws.close(code >= 1000 && code < 5000 && code !== 1005 && code !== 1006 ? code : 1000);
    } catch {}
    this.peerGone(ws);
  }

  webSocketError(ws: WebSocket): void {
    this.peerGone(ws);
  }

  private peerGone(ws: WebSocket): void {
    const role = this.ctx.getTags(ws)[0] as Role | undefined;
    if (!role) return;
    // A newer connection of the same role took over; the other side saw no gap.
    if (this.open(role, ws).length > 0) return;
    const other: Role = role === "agent" ? "client" : "agent";
    for (const p of this.open(other)) p.send(PEER_LEFT);
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);

    if (url.pathname === "/healthz") return new Response("ok");

    if (url.pathname === "/v1/presence" && request.method === "POST") {
      let body: unknown;
      try {
        body = await request.json();
      } catch {
        return new Response("bad json", { status: 400 });
      }
      if (!Array.isArray(body) || body.length > MAX_PRESENCE) {
        return new Response("bad request", { status: 400 });
      }
      const out = await Promise.all(
        body.map(async (d: { device_id?: string; secret?: string }) => {
          const id = d?.device_id ?? "";
          if (!validId(id) || !validSecret(d?.secret ?? null)) return { device_id: id, online: false };
          const online = await env.ROOMS.getByName(id).isOnline(d.secret!);
          return { device_id: id, online };
        }),
      );
      return Response.json(out);
    }

    // /ws/{agent|client}/{device_id}?secret=...
    if (parts[0] === "ws" && parts.length === 3) {
      const [, role, id] = parts;
      const secret = url.searchParams.get("secret");
      if (role !== "agent" && role !== "client") return new Response("not found", { status: 404 });
      if (!validId(id) || !validSecret(secret)) return new Response("bad request", { status: 400 });
      const doUrl = new URL("https://room/");
      doUrl.searchParams.set("role", role);
      doUrl.searchParams.set("secret", secret);
      return env.ROOMS.getByName(id).fetch(new Request(doUrl, request));
    }

    return new Response("remotectl relay", { status: 200 });
  },
} satisfies ExportedHandler<Env>;
