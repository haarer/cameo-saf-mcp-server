"""Minimal MCP streamable-HTTP client for the validation suite.

Why this exists: every live step in the suite (surface verify, model facts, cell runs)
has to talk to the Cameo MCP endpoint, and that endpoint requires a bearer token. A
client that silently omits the token gets a 401 and reports it as "Cameo is down",
which sends you hunting for the wrong fault.

Token resolution, in order:
  1. $CAMEO_MCP_TOKEN
  2. the `mcp.cameo.headers.Authorization` entry of the opencode config
     ($OPENCODE_CONFIG, else ~/.config/opencode/opencode.json)

Nothing here prints the token.
"""

from __future__ import annotations

import json
import os
import pathlib
import urllib.error
import urllib.request


class McpError(RuntimeError):
    """An MCP call failed. `hint` says what to do about it."""


def _config_path() -> pathlib.Path | None:
    # When OPENCODE_CONFIG is set it is authoritative. Falling back to the default when
    # it points at a file that does not exist hides the misconfiguration and quietly
    # authenticates with some other config's token.
    env = os.environ.get("OPENCODE_CONFIG")
    if env:
        p = pathlib.Path(env)
        return p if p.is_file() else None
    p = pathlib.Path.home() / ".config" / "opencode" / "opencode.json"
    return p if p.is_file() else None


def bearer_token() -> str | None:
    tok = os.environ.get("CAMEO_MCP_TOKEN")
    if tok:
        return tok.strip()
    cfg = _config_path()
    if cfg is None:
        return None
    try:
        d = json.loads(cfg.read_text(encoding="utf-8"))
    except Exception:
        return None
    for name, entry in (d.get("mcp") or {}).items():
        if not isinstance(entry, dict) or "url" not in entry:
            continue
        auth = (entry.get("headers") or {}).get("Authorization", "")
        if "cameo" in name and auth.lower().startswith("bearer "):
            return auth.split(None, 1)[1].strip()
    return None


class Client:
    def __init__(self, url: str, timeout: int = 120, token: str | None = None):
        self.url = url
        self.timeout = timeout
        self.token = token if token is not None else bearer_token()
        self.session: str | None = None
        self._id = 0

    # ---------------------------------------------------------------- transport

    def _post(self, payload: dict, session: str | None = None):
        req = urllib.request.Request(self.url, data=json.dumps(payload).encode())
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json, text/event-stream")
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        if session:
            req.add_header("Mcp-Session-Id", session)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.read().decode("utf-8", "replace"), r.headers.get("Mcp-Session-Id")
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:200]
            if e.code in (401, 403):
                raise McpError(
                    f"{e.code} from {self.url}: {body}\n"
                    f"  The server requires a bearer token. Set CAMEO_MCP_TOKEN, or point "
                    f"OPENCODE_CONFIG at an opencode config whose mcp.cameo entry has one."
                ) from e
            raise McpError(f"HTTP {e.code} from {self.url}: {body}") from e
        except urllib.error.URLError as e:
            raise McpError(
                f"cannot reach {self.url}: {e.reason}\n"
                f"  Cameo looks down. That is a different fault from a 401 -- check that "
                f"Cameo is running before suspecting the token."
            ) from e

    @staticmethod
    def _unwrap(raw: str) -> dict:
        for line in raw.splitlines():
            if line.startswith("data:"):
                return json.loads(line[5:].strip())
        return json.loads(raw)

    def connect(self) -> "Client":
        _, sid = self._post({"jsonrpc": "2.0", "id": 0, "method": "initialize",
                             "params": {"protocolVersion": "2024-11-05",
                                        "capabilities": {},
                                        "clientInfo": {"name": "validation", "version": "1"}}})
        self.session = sid
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
        return self

    def rpc(self, method: str, params: dict | None = None) -> dict:
        if self.session is None:
            self.connect()
        self._id += 1
        raw, _ = self._post({"jsonrpc": "2.0", "id": self._id, "method": method,
                             "params": params or {}}, self.session)
        r = self._unwrap(raw)
        if "error" in r:
            raise McpError(f"{method} failed: {r['error']}")
        return r.get("result", {})

    # ------------------------------------------------------------------ calls

    def tools(self) -> list[dict]:
        return self.rpc("tools/list").get("tools", [])

    def call(self, name: str, args: dict | None = None):
        res = self.rpc("tools/call", {"name": name, "arguments": args or {}})
        text = "\n".join(c["text"] for c in res.get("content", []) if c.get("type") == "text")
        if res.get("isError"):
            return {"_isError": True, "text": text}
        try:
            return json.loads(text)
        except Exception:
            return text

    def rows(self, name: str, args: dict | None = None) -> list[dict]:
        """Call a tool that returns a list, tolerating the common wrapper shapes."""
        r = self.call(name, args)
        if isinstance(r, list):
            return r
        if isinstance(r, dict):
            for k in ("elements", "rows", "results", "items", "nodes"):
                if isinstance(r.get(k), list):
                    return r[k]
        return []

    def resource(self, uri: str) -> str:
        res = self.rpc("resources/read", {"uri": uri})
        for c in res.get("contents", []):
            if c.get("text"):
                return c["text"]
        return json.dumps(res)

    def close(self) -> None:
        if self.session:
            try:
                self._post({"jsonrpc": "2.0", "id": 999, "method": "notifications/cancelled",
                            "params": {"requestId": self._id}}, self.session)
            except Exception:
                pass
            self.session = None
