#!/usr/bin/env python3
import argparse
import base64
import json
import os
import socket
import ssl
import struct
import sys
import urllib.parse


def _recvn(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf.extend(chunk)
    return bytes(buf)


def _handshake(sock, host, port, path, token):
    key = base64.b64encode(os.urandom(16)).decode()
    lines = [
        f"GET {path} HTTP/1.1",
        f"Host: {host}:{port}",
        "Upgrade: websocket",
        "Connection: Upgrade",
        f"Sec-WebSocket-Key: {key}",
        "Sec-WebSocket-Version: 13",
    ]
    if token:
        lines.append(f"X-Auth-Token: {token}")
    sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
    resp = bytearray()
    while b"\r\n\r\n" not in resp:
        resp.extend(_recvn(sock, 1))
    status = resp.split(b"\r\n", 1)[0].decode(errors="replace")
    if "101" not in status:
        raise ConnectionError(f"handshake failed: {status}")


def _send(sock, text):
    payload = text.encode()
    header = bytearray([0x81])
    length = len(payload)
    if length < 126:
        header.append(0x80 | length)
    elif length < 65536:
        header.append(0x80 | 126)
        header += struct.pack(">H", length)
    else:
        header.append(0x80 | 127)
        header += struct.pack(">Q", length)
    mask = os.urandom(4)
    header += mask
    sock.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))


def _read_message(sock):
    while True:
        b0, b1 = _recvn(sock, 2)
        opcode = b0 & 0x0F
        length = b1 & 0x7F
        if length == 126:
            length = struct.unpack(">H", _recvn(sock, 2))[0]
        elif length == 127:
            length = struct.unpack(">Q", _recvn(sock, 8))[0]
        payload = _recvn(sock, length) if length else b""
        if opcode == 0x8:
            raise ConnectionError("server closed")
        if opcode == 0x9:
            sock.sendall(bytes([0x8A, 0x80]) + os.urandom(4))
            continue
        if opcode in (0x1, 0x2):
            return payload.decode(errors="replace")


def _connect(url, token):
    parts = urllib.parse.urlparse(url)
    host = parts.hostname
    port = parts.port or (443 if parts.scheme == "wss" else 8090)
    path = parts.path or "/ws"
    raw = socket.create_connection((host, port))
    sock = ssl.create_default_context().wrap_socket(raw, server_hostname=host) if parts.scheme == "wss" else raw
    _handshake(sock, host, port, path, token)
    return sock


def _build(cmd, args):
    a = cmd
    if a.action in ("npm", "gradlew"):
        return {"action": "start", "process": a.action, "args": args, "cwd": a.cwd}
    if a.action == "input":
        return {"action": "input", "process": args[0], "data": args[1]}
    if a.action in ("stop", "status"):
        return {"action": a.action, "process": args[0]} if args else {"action": "list"}
    if a.action == "output":
        return {"action": "output", "process": args[0], "since": int(args[1]) if len(args) > 1 else 0}
    if a.action == "ls":
        return {"action": "ls", "path": args[0]}
    if a.action == "read":
        return {"action": "read", "path": args[0]}
    if a.action == "write":
        return {"action": "write", "path": args[0], "content": args[1], "makedirs": True}
    if a.action == "mkdir":
        return {"action": "mkdir", "path": args[0]}
    if a.action == "delete":
        return {"action": "delete", "path": args[0], "recursive": True}
    if a.action == "copy":
        return {"action": "copy", "source": args[0], "dest": args[1]}
    if a.action == "raw":
        return json.loads(args[0])
    raise SystemExit(f"unknown command: {a.action}")


def main():
    ap = argparse.ArgumentParser(description="route commands to the relay")
    ap.add_argument("--url", default=os.environ.get("RELAY_URL", "ws://127.0.0.1:8090/ws"))
    ap.add_argument("--token", default=os.environ.get("RELAY_TOKEN"))
    ap.add_argument("--cwd")
    ap.add_argument("--no-follow", action="store_true")
    ap.add_argument("action")
    ap.add_argument("rest", nargs=argparse.REMAINDER)
    a = ap.parse_args()

    frame = _build(a, a.rest)
    frame["id"] = "1"
    process = frame.get("process")
    streaming = frame.get("action") == "start" and not a.no_follow

    sock = _connect(a.url, a.token)
    try:
        _send(sock, json.dumps(frame))
        while True:
            msg = json.loads(_read_message(sock))
            t = msg.get("type")
            if t == "output" and msg.get("process") == process:
                sys.stdout.write(msg.get("text", "") + "\n")
                sys.stdout.flush()
            elif t == "exited" and msg.get("process") == process:
                code = msg.get("exitCode")
                sys.stderr.write(f"[exit {code}]\n")
                return 0 if code == 0 else 1
            elif t == "reply" and msg.get("id") == "1":
                if not msg.get("ok"):
                    sys.stderr.write(str(msg.get("error")) + "\n")
                    return 1
                if not streaming:
                    result = msg.get("result")
                    sys.stdout.write(json.dumps(result, indent=2) + "\n" if result is not None else "ok\n")
                    return 0
    finally:
        sock.close()


if __name__ == "__main__":
    sys.exit(main())
