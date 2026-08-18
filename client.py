#!/usr/bin/env python3
import asyncio
import json
import os

import aiohttp

_CFG = {}
_cfg_path = os.environ.get("APP_CONFIG", "config.json")
if os.path.exists(_cfg_path):
    try:
        with open(_cfg_path) as _f:
            _CFG = json.load(_f)
    except Exception as _e:  # noqa: BLE001
        print(f"[client] warning: could not read {_cfg_path}: {_e}")


def cfg(env_key, cfg_key, default=None):
    if env_key in os.environ:
        return os.environ[env_key]
    return _CFG.get(cfg_key, default)


SERVER_HOST = cfg("SERVER_HOST", "server_host", "127.0.0.1")
SERVER_PORT = int(cfg("SERVER_PORT", "server_port", 8090))
TARGET_URL = str(cfg("TARGET_URL", "target_url", "http://localhost:8080")).rstrip("/")
TOKEN = cfg("AUTH_TOKEN", "token", None)
SCHEME = "wss" if str(cfg("USE_TLS", "tls", "0")) in ("1", "true", "True") else "ws"


async def run_command(session, cmd):
    method = str(cmd.get("method", "GET")).upper()
    path = cmd.get("path", "/")
    url = TARGET_URL + (path if path.startswith("/") else "/" + path)
    body = cmd.get("body")
    headers = cmd.get("headers") or {}
    result = {"id": cmd.get("id")}

    json_body = body if isinstance(body, (dict, list)) else None
    raw_body = None if json_body is not None else body

    try:
        async with session.request(
            method, url,
            json=json_body,
            data=raw_body,
            headers=headers,
            timeout=aiohttp.ClientTimeout(total=30),
        ) as resp:
            text = await resp.text()
            result["status"] = resp.status
            if "application/json" in resp.headers.get("Content-Type", ""):
                try:
                    result["body"] = json.loads(text)
                except json.JSONDecodeError:
                    result["body"] = text
            else:
                result["body"] = text
    except Exception as e:  # noqa: BLE001
        result["status"] = None
        result["error"] = f"{type(e).__name__}: {e}"
    return result


async def connect_once():
    url = f"{SCHEME}://{SERVER_HOST}:{SERVER_PORT}/ws"
    if TOKEN:
        url += f"?token={TOKEN}"
    async with aiohttp.ClientSession() as session:
        print(f"[client] connecting to {url}")
        async with session.ws_connect(url, heartbeat=30) as ws:
            print(f"[client] connected; forwarding to {TARGET_URL}")
            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.TEXT:
                    try:
                        cmd = json.loads(msg.data)
                    except json.JSONDecodeError:
                        continue
                    print(f"[client] running {cmd.get('method')} {cmd.get('path')} "
                          f"(id={cmd.get('id')})")
                    result = await run_command(session, cmd)
                    await ws.send_str(json.dumps(result))
                elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                    break


async def main():
    backoff = 1
    max_backoff = 30
    while True:
        try:
            await connect_once()
            print("[client] connection closed; reconnecting...")
            backoff = 1
            await asyncio.sleep(1)
        except Exception as e:  # noqa: BLE001
            print(f"[client] connect failed: {type(e).__name__}: {e}; retry in {backoff}s")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, max_backoff)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
