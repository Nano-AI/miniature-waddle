#!/usr/bin/env python3
import argparse
import json
import os
import time
import urllib.request


def _req(url, token, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Auth-Token", token)
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("method")
    ap.add_argument("path")
    ap.add_argument("--body")
    ap.add_argument("--server", default=os.environ.get("SERVER_URL", "http://localhost:8090"))
    ap.add_argument("--token", default=os.environ.get("AUTH_TOKEN"))
    ap.add_argument("--wait", type=float, default=15)
    args = ap.parse_args()

    base = args.server.rstrip("/")
    payload = {"method": args.method, "path": args.path}
    if args.body is not None:
        try:
            payload["body"] = json.loads(args.body)
        except json.JSONDecodeError:
            payload["body"] = args.body

    resp = _req(base + "/enqueue", args.token, payload)
    cmd_id = resp["id"]
    print(f"enqueued id={cmd_id} client_connected={resp.get('client_connected')}")

    deadline = time.time() + args.wait
    while time.time() < deadline:
        rec = _req(base + f"/results/{cmd_id}", args.token)
        if rec.get("status") in ("done", "error"):
            print(json.dumps(rec, indent=2))
            return
        time.sleep(0.5)
    print(f"timed out after {args.wait}s; fetch later: curl {base}/results/{cmd_id}")


if __name__ == "__main__":
    main()
