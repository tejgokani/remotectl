# remotectl relay (Cloudflare)

A Worker plus one Durable Object per laptop. It routes opaque, end-to-end-encrypted frames between a
laptop agent and a phone, and answers "which of my laptops are online?".

```bash
pnpm install
pnpm exec wrangler login
pnpm exec wrangler deploy      # prints https://remotectl-relay.<you>.workers.dev
```

Use `wss://remotectl-relay.<you>.workers.dev` as the relay in `remotectl pair --relay ...`.

Local development: `pnpm dev` serves it on http://127.0.0.1:8787 with the real Durable Object runtime.

Endpoints: `GET /ws/{agent|client}/{device_id}?secret=…` (WebSocket), `POST /v1/presence`, `GET /healthz`.
The relay stores only a SHA-256 of each room's secret. It never sees keys or plaintext.
