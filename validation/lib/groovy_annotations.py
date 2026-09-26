"""Groovy annotation reader for the MCP handler scripts.

The tool surface is declared with annotations in `scripts/*.groovy`:

    @McpTool(name = "create_element", description = "Create an element ...")
    @McpToolArgument(name = "parentId", type = "string", description = "...", required = true)
    Map createElement(Map<String, Object> args) { ... }

A regex is not good enough here. In this repo the annotations appear as:

  - one line or spread over many
  - double-quoted, single-quoted, or triple-single-quoted literals
  - descriptions containing `(`, `)`, `,` and `=` -- all of which a naive
    `key = value` split or paren matcher gets wrong

So this module scans literals properly. It is a reader, not a parser: it
understands enough Groovy to find annotation argument lists and nothing more.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterator

# Annotation names this module knows how to read, and the argument that carries
# the identifier. `uri` rather than `name` for resources (see McpResource.java).
KNOWN = {
    "McpTool": ("name", "tool"),
    "McpToolArgument": ("name", "tool_argument"),
    "McpResource": ("uri", "resource"),
    "McpPrompt": ("name", "prompt"),
}

_ANNOTATION_RE = re.compile(r"@(" + "|".join(KNOWN) + r")\s*\(")
_METHOD_RE = re.compile(r"^\s*(?:[\w.<>\[\], ]+\s+)?(\w+)\s*\(", re.MULTILINE)

# A GString interpolation would make the description dynamic; record it rather than
# silently reporting a literal that is not what reaches the model.
_INTERPOLATION_RE = re.compile(r"\$\{")

# Escape sequences Groovy resolves inside a single- or double-quoted literal. Inside a
# triple-single-quoted literal a backslash is an ordinary character, which is why this
# table is only applied when `triple` is false.
_ESCAPES = {
    "n": "\n",
    "t": "\t",
    "r": "\r",
    "b": "\b",
    "f": "\f",
    "0": "\0",
    "\\": "\\",
    "'": "'",
    '"': '"',
    "$": "$",
    "\n": "",
}


@dataclass
class Annotation:
    kind: str
    identifier: str
    args: dict
    line: int
    source: str
    group: str = ""
    dynamic: list = field(default_factory=list)
    interpolated: bool = False
    malformed_literals: list = field(default_factory=list)


def _quote_run(src: str, i: int) -> int:
    """Length of the run of identical quote characters starting at `i`."""
    q = src[i]
    j = i
    while j < len(src) and src[j] == q:
        j += 1
    return j - i


def read_literal(src: str, i: int) -> tuple[str | None, int]:
    """Read the string literal at `i`.

    Returns (value, index_past_the_literal). The value is None when the quote at `i`
    opens nothing usable -- an unterminated single-quoted literal, which cannot span
    a line in Groovy. Callers must then treat the quote as an ordinary character, or
    they will swallow the closing paren of the annotation they are inside.

    Triple-quoted, quote run at the end: Groovy does not stop at the first `'''`. For a
    closing run of N >= 3 quotes it absorbs N - 3 of them into the content. Verified
    against Groovy 4.0.32's AST: `'''X''''` yields `X'` and `'''X'''''` yields `X''`.
    One description in `scripts/saf_tools.groovy` depends on this -- it ends
    `detector.` followed by four quotes and the real value keeps the apostrophe. A
    naive "stop at the first `'''`" leaves a stray quote that desynchronises the
    whole file.
    """
    quote = src[i]
    triple = src.startswith(quote * 3, i)
    delim = quote * 3 if triple else quote
    out: list[str] = []
    j = i + len(delim)
    n = len(src)
    while j < n:
        c = src[j]
        if c == "\\" and not triple:
            if j + 1 < n:
                nxt = src[j + 1]
                if nxt == "u" and j + 5 < n:
                    try:
                        out.append(chr(int(src[j + 2 : j + 6], 16)))
                        j += 6
                        continue
                    except ValueError:
                        pass
                out.append(_ESCAPES.get(nxt, nxt))
                j += 2
            else:
                j += 1
            continue
        if c == quote:
            run = _quote_run(src, j)
            if not triple:
                return "".join(out), j + run
            if run < 3:
                out.append(quote * run)
                j += run
                continue
            # The literal ends at the end of the run; a run longer than three means
            # the surplus quotes are content, not another delimiter.
            out.append(quote * (run - 3))
            return "".join(out), j + run
        if not triple and c == "\n":
            return None, i
        out.append(c)
        j += 1
    return (None, i) if not triple else ("".join(out), n)


# Returned by callers that need to know a quote opened nothing.
MALFORMED = None


def _skip_trivia(src: str, i: int) -> int:
    """Skip whitespace and comments, so annotation internals can contain either."""
    n = len(src)
    while i < n:
        if src[i].isspace():
            i += 1
        elif src.startswith("//", i):
            nl = src.find("\n", i)
            i = n if nl == -1 else nl + 1
        elif src.startswith("/*", i):
            end = src.find("*/", i + 2)
            i = n if end == -1 else end + 2
        else:
            break
    return i


def _read_balanced(src: str, open_paren: int) -> tuple[str, int, list[int]]:
    """Return the contents of the paren group at `open_paren`, the index past it,
    and the line numbers of any malformed literals encountered on the way."""
    depth = 0
    i = open_paren
    n = len(src)
    anomalies: list[int] = []
    while i < n:
        c = src[i]
        if c in "\"'":
            value, j = read_literal(src, i)
            if value is None:
                anomalies.append(src.count("\n", 0, i) + 1)
                i += 1
                continue
            i = j
            continue
        i = _skip_trivia(src, i)
        if i >= n:
            break
        c = src[i]
        if c in "\"'":
            value, j = read_literal(src, i)
            if value is None:
                anomalies.append(src.count("\n", 0, i) + 1)
                i += 1
                continue
            i = j
        elif c == "(":
            depth += 1
            i += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return src[open_paren + 1 : i], i + 1, anomalies
            i += 1
        else:
            i += 1
    return src[open_paren + 1 :], n, anomalies


def _split_top_level(content: str) -> list[str]:
    """Split annotation arguments on commas that are not inside a literal or a nest."""
    parts, buf, depth = [], [], 0
    i, n = 0, len(content)
    while i < n:
        c = content[i]
        if c in "\"'":
            value, j = read_literal(content, i)
            if value is None:
                buf.append(c)
                i += 1
                continue
            buf.append(content[i:j])
            i = j
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    if "".join(buf).strip():
        parts.append("".join(buf))
    return [p.strip() for p in parts if p.strip()]


def _coerce(raw: str) -> object:
    """Turn an annotation argument value into a Python value.

    Only literals are resolved. Anything else (a variable, a concatenation) becomes
    the `DYNAMIC` marker, because guessing would put a fabricated value into a
    measurement.
    """
    raw = raw.strip()
    if raw in ("true", "false"):
        return raw == "true"
    if raw[:1] in ("'", '"'):
        value, end = read_literal(raw, 0)
        if value is None or read_literal(raw.rstrip(), 0)[1] != len(raw.rstrip()):
            return DYNAMIC
        return value
    return DYNAMIC


class _Dynamic:
    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return "<dynamic>"

    def __bool__(self) -> bool:
        return False


DYNAMIC = _Dynamic()


def parse_file(path) -> list[Annotation]:
    """Read every known annotation out of one Groovy source file."""
    with open(path, "r", encoding="utf-8") as fh:
        src = fh.read()

    found: list[Annotation] = []
    for match in _ANNOTATION_RE.finditer(src):
        kind = match.group(1)
        open_paren = src.index("(", match.end() - 1)
        content, end, anomalies = _read_balanced(src, open_paren)
        line = src.count("\n", 0, match.start()) + 1

        args: dict = {}
        dynamic: list = []
        interpolated = False
        for part in _split_top_level(content):
            if "=" not in part:
                continue
            key, _, raw = part.partition("=")
            key = key.strip()
            if not key:
                continue
            value = _coerce(raw)
            if value is DYNAMIC:
                dynamic.append(key)
            elif isinstance(value, str) and _INTERPOLATION_RE.search(value):
                interpolated = True
            args[key] = value

        id_key, group = KNOWN[kind]
        identifier = args.get(id_key)
        if not isinstance(identifier, str):
            identifier = f"<{kind} at line {line}>"
            dynamic.append(id_key)

        found.append(
            Annotation(
                kind=kind,
                identifier=identifier,
                args=args,
                line=line,
                source=str(path),
                group=group,
                dynamic=dynamic,
                interpolated=interpolated,
                malformed_literals=anomalies,
            )
        )
    return found


def method_after(src: str, index: int) -> str | None:
    """Best-effort name of the method whose annotations end just before `index`."""
    match = _METHOD_RE.search(src, index)
    return match.group(1) if match else None


def read_scripts(script_paths) -> list[Annotation]:
    out: list[Annotation] = []
    for path in sorted(script_paths):
        out.extend(parse_file(path))
    return out
