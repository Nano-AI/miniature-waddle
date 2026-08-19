#!/usr/bin/env python3
import asyncio
import base64
import json
import os
import pathlib
import shutil
import subprocess
import time
from collections import deque

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

SYSTEM_PREFIX = "/system/"
ALLOWED_SHELLS = ("cmd", "powershell")
MAX_OUTPUT_LINES = int(cfg("EXEC_MAX_LINES", "exec_max_lines", 2000))
DEFAULT_EXEC_TIMEOUT = float(cfg("EXEC_DEFAULT_TIMEOUT", "exec_timeout", 30))
_NO_WINDOW_FLAG = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _shell_args(shell, command):
    if shell == "powershell":
        return ["powershell", "-NoProfile", "-NonInteractive", "-Command", command]
    return ["cmd", "/c", command]


class ProcManager:
    def __init__(self):
        self.procs = {}

    async def spawn(self, shell, command, cwd, mode, timeout):
        args = _shell_args(shell, command)
        proc = await asyncio.create_subprocess_exec(
            *args,
            cwd=cwd or None,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            creationflags=_NO_WINDOW_FLAG,
        )
        pid = proc.pid
        entry = {
            "pid": pid,
            "shell": shell,
            "command": command,
            "cwd": cwd,
            "started_at": time.time(),
            "proc": proc,
            "stdout": deque(maxlen=MAX_OUTPUT_LINES),
            "stderr": deque(maxlen=MAX_OUTPUT_LINES),
            "running": True,
            "exit_code": None,
        }
        self.procs[pid] = entry
        stdout_task = asyncio.create_task(self._pump(entry, proc.stdout, "stdout"))
        stderr_task = asyncio.create_task(self._pump(entry, proc.stderr, "stderr"))
        wait_task = asyncio.create_task(self._wait(entry, proc))
        entry["_stdout_task"] = stdout_task
        entry["_stderr_task"] = stderr_task
        entry["_wait_task"] = wait_task

        if mode == "background":
            return {"pid": pid, "running": True}

        try:
            await asyncio.wait_for(asyncio.shield(wait_task), timeout=timeout)
        except asyncio.TimeoutError:
            pass
        else:
            await asyncio.gather(stdout_task, stderr_task)
        return self.output(pid)

    async def _pump(self, entry, stream, key):
        buf = entry[key]
        while True:
            line = await stream.readline()
            if not line:
                break
            buf.append(line.decode(errors="replace"))

    async def _wait(self, entry, proc):
        code = await proc.wait()
        entry["running"] = False
        entry["exit_code"] = code

    def output(self, pid):
        entry = self.procs.get(pid)
        if not entry:
            return None
        return {
            "pid": pid,
            "running": entry["running"],
            "exit_code": entry["exit_code"],
            "stdout": "".join(entry["stdout"]),
            "stderr": "".join(entry["stderr"]),
        }

    async def kill(self, pid):
        entry = self.procs.get(pid)
        if not entry:
            return {"pid": pid, "error": "unknown pid"}
        if not entry["running"]:
            return {"pid": pid, "killed": False, "already_exited": True,
                     "exit_code": entry["exit_code"]}
        if os.name == "nt":
            k = await asyncio.create_subprocess_exec(
                "taskkill", "/PID", str(pid), "/T", "/F",
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            )
            await k.communicate()
        else:
            entry["proc"].kill()
        try:
            await asyncio.wait_for(entry["_wait_task"], timeout=5)
        except asyncio.TimeoutError:
            pass
        return {"pid": pid, "killed": True, "running": entry["running"],
                "exit_code": entry["exit_code"]}

    def list(self):
        return [
            {
                "pid": pid,
                "command": e["command"],
                "shell": e["shell"],
                "cwd": e["cwd"],
                "running": e["running"],
                "started_at": e["started_at"],
                "exit_code": e["exit_code"],
            }
            for pid, e in self.procs.items()
        ]


proc_manager = ProcManager()


def is_system_path(path):
    return path.startswith(SYSTEM_PREFIX)


async def handle_system_command(cmd):
    result = {"id": cmd.get("id")}
    method = str(cmd.get("method", "GET")).upper()
    path = cmd.get("path", "/")
    body = cmd.get("body") or {}
    try:
        if method == "POST" and path == "/system/exec":
            shell = body.get("shell", "cmd")
            if shell not in ALLOWED_SHELLS:
                raise ValueError(f"shell must be one of {ALLOWED_SHELLS}")
            command = body.get("command")
            if not command:
                raise ValueError("missing 'command'")
            mode = body.get("mode", "wait")
            if mode not in ("wait", "background"):
                raise ValueError("mode must be 'wait' or 'background'")
            timeout = float(body.get("timeout", DEFAULT_EXEC_TIMEOUT))
            out = await proc_manager.spawn(shell, command, body.get("cwd"), mode, timeout)
            result["status"] = 200
            result["body"] = out

        elif method == "GET" and path == "/system/proc":
            result["status"] = 200
            result["body"] = proc_manager.list()

        elif method == "GET" and path.startswith("/system/proc/") and path.endswith("/output"):
            pid = int(path.split("/")[3])
            out = proc_manager.output(pid)
            result["status"] = 200 if out is not None else 404
            result["body"] = out if out is not None else {"error": "unknown pid"}

        elif method == "POST" and path.startswith("/system/proc/") and path.endswith("/kill"):
            pid = int(path.split("/")[3])
            out = await proc_manager.kill(pid)
            result["status"] = 404 if out.get("error") else 200
            result["body"] = out

        else:
            result["status"] = 404
            result["body"] = {"error": f"unknown system path: {method} {path}"}
    except Exception as e:  # noqa: BLE001
        result["status"] = None
        result["error"] = f"{type(e).__name__}: {e}"
    return result


FILES_PREFIX = "/files/"


def is_files_path(path):
    return path.startswith(FILES_PREFIX)


def _target_path(body):
    p = body.get("path")
    if not p:
        raise ValueError("missing 'path'")
    return pathlib.Path(p)


def _read_file(body):
    p = _target_path(body)
    if not p.is_file():
        raise FileNotFoundError(f"no such file: {p}")
    raw = p.read_bytes()
    encoding = body.get("encoding")
    if encoding not in (None, "utf-8", "base64"):
        raise ValueError("encoding must be 'utf-8' or 'base64'")
    if encoding != "base64":
        try:
            return {"path": str(p), "content": raw.decode("utf-8"),
                     "encoding": "utf-8", "size": len(raw)}
        except UnicodeDecodeError:
            if encoding == "utf-8":
                raise
    return {"path": str(p), "content": base64.b64encode(raw).decode("ascii"),
             "encoding": "base64", "size": len(raw)}


def _write_file(body):
    p = _target_path(body)
    content = body.get("content")
    if content is None:
        raise ValueError("missing 'content'")
    encoding = body.get("encoding", "utf-8")
    if encoding == "base64":
        raw = base64.b64decode(content)
    elif encoding == "utf-8":
        raw = content.encode("utf-8")
    else:
        raise ValueError("encoding must be 'utf-8' or 'base64'")
    if body.get("makedirs"):
        p.parent.mkdir(parents=True, exist_ok=True)
    mode = body.get("mode", "overwrite")
    if mode == "append":
        with open(p, "ab") as f:
            f.write(raw)
    elif mode == "overwrite":
        p.write_bytes(raw)
    else:
        raise ValueError("mode must be 'overwrite' or 'append'")
    return {"path": str(p), "bytes_written": len(raw), "mode": mode}


def _delete_path(body):
    p = _target_path(body)
    if not p.exists():
        raise FileNotFoundError(f"no such path: {p}")
    if p.is_dir():
        if not body.get("recursive"):
            raise ValueError(f"'{p}' is a directory; pass recursive:true to delete it")
        shutil.rmtree(p)
        return {"path": str(p), "deleted": True, "was_dir": True}
    p.unlink()
    return {"path": str(p), "deleted": True, "was_dir": False}


def _list_dir(body):
    p = _target_path(body)
    if not p.is_dir():
        raise FileNotFoundError(f"no such directory: {p}")
    entries = []
    for child in p.iterdir():
        st = child.stat()
        entries.append({
            "name": child.name,
            "is_dir": child.is_dir(),
            "size": st.st_size,
            "modified": st.st_mtime,
        })
    return {"path": str(p), "entries": entries}


async def handle_files_command(cmd):
    result = {"id": cmd.get("id")}
    method = str(cmd.get("method", "GET")).upper()
    path = cmd.get("path", "/")
    body = cmd.get("body") or {}
    try:
        if method == "GET" and path == "/files/read":
            result["status"] = 200
            result["body"] = _read_file(body)
        elif method == "POST" and path == "/files/write":
            result["status"] = 200
            result["body"] = _write_file(body)
        elif method == "POST" and path == "/files/delete":
            result["status"] = 200
            result["body"] = _delete_path(body)
        elif method == "GET" and path == "/files/ls":
            result["status"] = 200
            result["body"] = _list_dir(body)
        else:
            result["status"] = 404
            result["body"] = {"error": f"unknown files path: {method} {path}"}
    except FileNotFoundError as e:
        result["status"] = 404
        result["body"] = {"error": str(e)}
    except Exception as e:  # noqa: BLE001
        result["status"] = None
        result["error"] = f"{type(e).__name__}: {e}"
    return result


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
                    cmd_path = cmd.get("path", "")
                    if is_system_path(cmd_path):
                        result = await handle_system_command(cmd)
                    elif is_files_path(cmd_path):
                        result = await handle_files_command(cmd)
                    else:
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
