import os
import sys
import time
import httpx
import json

SERVER_URL = os.environ.get("SERVER_URL", "http://localhost:18741")
TIMEOUT = 5  # short timeout to fail fast at each step


def step(label, duration):
    print(f"[+{duration:.2f}s] {label}")


def main():
    t0 = time.monotonic()

    client = httpx.Client(base_url=SERVER_URL, timeout=TIMEOUT)

    try:
        print("=== Step 1: Health check ===")
        try:
            r = client.get("/")
            step(f"GET / -> {r.status_code} {r.json()}", time.monotonic() - t0)
        except Exception as e:
            step(f"GET / FAILED: {e}", time.monotonic() - t0)
            sys.exit(1)

        print("\n=== Step 2: MCP initialize ===")
        try:
            payload = {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "diag-client", "version": "0.0.1"}
                }
            }
            r = client.post("/mcp", json=payload)
            step(f"POST /mcp initialize -> {r.status_code}", time.monotonic() - t0)
            session_id = r.headers.get("mcp-session-id")
            step(f"  Mcp-Session-Id: {session_id}", time.monotonic() - t0)
            body = r.json()
            step(f"  result: {json.dumps(body.get('result', {}).get('serverInfo'), indent=2)}", time.monotonic() - t0)

            if r.status_code != 200 or session_id is None:
                step("INIT FAILED, stopping here", time.monotonic() - t0)
                sys.exit(1)
        except Exception as e:
            step(f"POST /mcp initialize FAILED: {e}", time.monotonic() - t0)
            sys.exit(1)

        print("\n=== Step 3: notifications/initialized ===")
        try:
            r = client.post("/mcp",
                            json={"jsonrpc": "2.0", "method": "notifications/initialized"},
                            headers={"Mcp-Session-Id": session_id})
            step(f"POST notifications/initialized -> {r.status_code}", time.monotonic() - t0)
        except Exception as e:
            step(f"POST notifications/initialized FAILED: {e}", time.monotonic() - t0)
            sys.exit(1)

        print("\n=== Step 4: tools/list ===")
        try:
            r = client.post("/mcp",
                            json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                            headers={"Mcp-Session-Id": session_id})
            step(f"POST tools/list -> {r.status_code} content-type={r.headers.get('content-type','')}", time.monotonic() - t0)
            step(f"  body length: {len(r.text)} bytes", time.monotonic() - t0)

            # Parse SSE events
            events = []
            for block in r.text.split("\n\n"):
                if not block.strip():
                    continue
                event = {}
                for line in block.strip().split("\n"):
                    if line.startswith("event:"):
                        event["event"] = line[6:].strip()
                    elif line.startswith("data:"):
                        event["data"] = line[5:].strip()
                if event:
                    events.append(event)

            step(f"  SSE events count: {len(events)}", time.monotonic() - t0)

            if events:
                data = json.loads(events[0]["data"])
                tools = data.get("result", {}).get("tools", [])
                step(f"  tools: {[t['name'] for t in tools]}", time.monotonic() - t0)
            else:
                step(f"  raw body: {r.text[:500]}", time.monotonic() - t0)

        except Exception as e:
            step(f"POST tools/list FAILED: {e}", time.monotonic() - t0)
            sys.exit(1)

        print("\n=== Step 5: tools/call echo ===")
        try:
            r = client.post("/mcp",
                            json={
                                "jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                "params": {"name": "echo", "arguments": {"message": "hello from diag"}}
                            },
                            headers={"Mcp-Session-Id": session_id})
            step(f"POST tools/call echo -> {r.status_code}", time.monotonic() - t0)

            events = []
            for block in r.text.split("\n\n"):
                if not block.strip():
                    continue
                event = {}
                for line in block.strip().split("\n"):
                    if line.startswith("event:"):
                        event["event"] = line[6:].strip()
                    elif line.startswith("data:"):
                        event["data"] = line[5:].strip()
                if event:
                    events.append(event)

            step(f"  SSE events count: {len(events)}", time.monotonic() - t0)

            if events:
                data = json.loads(events[0]["data"])
                if "result" in data:
                    content = data["result"].get("content", [])
                    step(f"  result content: {content}", time.monotonic() - t0)
                elif "error" in data:
                    step(f"  error: {data['error']}", time.monotonic() - t0)
            else:
                step(f"  raw body: {r.text[:500]}", time.monotonic() - t0)

        except Exception as e:
            step(f"POST tools/call echo FAILED: {e}", time.monotonic() - t0)
            sys.exit(1)

        print(f"\n=== All steps passed in {time.monotonic()-t0:.2f}s ===")
    finally:
        client.close()


if __name__ == "__main__":
    main()
