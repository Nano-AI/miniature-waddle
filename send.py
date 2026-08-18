#!/usr/bin/env python3
"""
Enqueue one command, wait, return the result.

Exit codes (for agentic branching without parsing):
    0  success  (status=done and 2xx)
    1  error    (status=error or non-2xx)
    2  timeout
    3  no agent connected

--quiet: print ONLY the result payload (compact JSON) to stdout on success,
         or the error string to stderr on failure. Token-minimal.
"""
import argparse
import json
import os
import sys
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


def _emit_body(body):
    if isinstance(body, (dict, list)):
        sys.stdout.write(json.dumps(body, separators=(",", ":")) + "\n")
    elif body is not None:
        sys.stdout.write(str(body) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("method")
    ap.add_argument("path")
    ap.add_argument("--body")
    ap.add_argument("--server", default=os.environ.get("SERVER_URL", "http://localhost:8090"))
    ap.add_argument("--token", default=os.environ.get("AUTH_TOKEN"))
    ap.add_argument("--wait", type=float, default=15)
    ap.add_argument("-q", "--quiet", action="store_true",
                    help="print only the result payload; rely on exit code for status")
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
    if resp.get("client_connected") is False:
        sys.stderr.write("no agent connected\n")
        return 3
    if not args.quiet:
        print(f"enqueued id={cmd_id} client_connected={resp.get('client_connected')}")

    deadline = time.time() + args.wait
    while time.time() < deadline:
        rec = _req(base + f"/results/{cmd_id}", args.token)
        status = rec.get("status")
        if status in ("done", "error"):
            http = rec.get("http_status")
            ok = status == "done" and isinstance(http, int) and 200 <= http < 300
            if args.quiet:
                if ok:
                    _emit_body(rec.get("response_body"))
                else:
                    err = rec.get("error") or f"http {http}"
                    sys.stderr.write(str(err) + "\n")
            else:
                print(json.dumps(rec, indent=2))
            return 0 if ok else 1
        time.sleep(0.5)

    (sys.stderr if args.quiet else sys.stdout).write(
        f"timeout after {args.wait}s (id={cmd_id})\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
