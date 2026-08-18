#!/usr/bin/env python3
import asyncio
import json
import os
import time
import uuid
from collections import OrderedDict

from aiohttp import WSMsgType, web

_CFG = {}
_cfg_path = os.environ.get("APP_CONFIG", "config.json")
if os.path.exists(_cfg_path):
    try:
        with open(_cfg_path) as _f:
            _CFG = json.load(_f)
    except Exception as _e:  # noqa: BLE001
        print(f"[server] warning: could not read {_cfg_path}: {_e}")


def cfg(env_key, cfg_key, default=None):
    if env_key in os.environ:
        return os.environ[env_key]
    return _CFG.get(cfg_key, default)


HOST = cfg("SERVER_HOST", "host", "0.0.0.0")
PORT = int(cfg("SERVER_PORT", "port", 8090))
TOKEN = cfg("AUTH_TOKEN", "token", None)


class Hub:
    def __init__(self):
        self.pending = asyncio.Queue()
        self.results = OrderedDict()
        self.client = None

    def enqueue(self, cmd):
        cmd_id = uuid.uuid4().hex[:12]
        rec = {
            "id": cmd_id,
            "method": str(cmd.get("method", "GET")).upper(),
            "path": cmd.get("path", "/"),
            "body": cmd.get("body"),
            "headers": cmd.get("headers") or {},
            "status": "queued",
            "enqueued_at": time.time(),
        }
        self.results[cmd_id] = rec
        self.pending.put_nowait(rec)
        return rec

    def record_result(self, msg):
        cmd_id = msg.get("id")
        rec = self.results.get(cmd_id) or {"id": cmd_id}
        self.results[cmd_id] = rec
        rec["status"] = "error" if msg.get("error") else "done"
        rec["http_status"] = msg.get("status")
        rec["response_body"] = msg.get("body")
        rec["error"] = msg.get("error")
        rec["completed_at"] = time.time()
        return rec


hub = Hub()


def authorized(request):
    if not TOKEN:
        return True
    supplied = request.headers.get("X-Auth-Token") or request.query.get("token")
    return supplied == TOKEN


async def ws_handler(request):
    if not authorized(request):
        return web.Response(status=401, text="bad token")

    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    peer = request.remote
    print(f"[server] client connected from {peer}")
    hub.client = ws

    async def sender():
        while True:
            rec = await hub.pending.get()
            cmd = {
                "id": rec["id"],
                "method": rec["method"],
                "path": rec["path"],
                "body": rec["body"],
                "headers": rec["headers"],
            }
            try:
                await ws.send_str(json.dumps(cmd))
                rec["status"] = "sent"
                print(f"[server] -> sent {rec['method']} {rec['path']} (id={rec['id']})")
            except Exception:  # noqa: BLE001
                rec["status"] = "queued"
                hub.pending.put_nowait(rec)
                break

    send_task = asyncio.create_task(sender())
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                except json.JSONDecodeError:
                    print(f"[server] bad json from client: {msg.data[:200]}")
                    continue
                rec = hub.record_result(data)
                print(f"[server] <- result id={rec.get('id')} "
                      f"http_status={rec.get('http_status')} error={rec.get('error')}")
                body = rec.get("response_body")
                print(json.dumps(body, indent=2) if isinstance(body, (dict, list)) else body)
            elif msg.type == WSMsgType.ERROR:
                print(f"[server] ws error: {ws.exception()}")
    finally:
        send_task.cancel()
        if hub.client is ws:
            hub.client = None
        print(f"[server] client {peer} disconnected")
    return ws


async def enqueue_handler(request):
    if not authorized(request):
        return web.Response(status=401, text="bad token")
    try:
        cmd = await request.json()
    except Exception:  # noqa: BLE001
        return web.json_response({"error": "invalid json"}, status=400)
    rec = hub.enqueue(cmd)
    return web.json_response({
        "id": rec["id"],
        "status": rec["status"],
        "client_connected": hub.client is not None,
    })


async def results_handler(request):
    if not authorized(request):
        return web.Response(status=401, text="bad token")
    cmd_id = request.match_info.get("id")
    if cmd_id:
        rec = hub.results.get(cmd_id)
        if not rec:
            return web.json_response({"error": "unknown id"}, status=404)
        return web.json_response(rec)
    return web.json_response(list(hub.results.values()))


async def status_handler(request):
    return web.json_response({
        "client_connected": hub.client is not None,
        "queued": hub.pending.qsize(),
        "results": len(hub.results),
    })


def make_app():
    app = web.Application()
    app.router.add_get("/", status_handler)
    app.router.add_get("/ws", ws_handler)
    app.router.add_post("/enqueue", enqueue_handler)
    app.router.add_get("/results", results_handler)
    app.router.add_get("/results/{id}", results_handler)
    return app


if __name__ == "__main__":
    print(f"[server] listening on {HOST}:{PORT}  (token {'on' if TOKEN else 'off'})")
    web.run_app(make_app(), host=HOST, port=PORT, print=None)
