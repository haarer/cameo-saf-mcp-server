# MCP Surface Review — from the 2026-08-23 session (English translation & dissolution)

Status: dissolved. This page is the slim English successor of the original
German review (2026-08-23). Its content has been redistributed: the fixed
server-side weakness now lives in ADR-0015, the knowledge measures in
`AGENTS.md` and `docs/cameo-2026x-api-notes.md`, and the remaining
optimizations in `plan.md` iteration 9 (and 8 for session GC).

## Provenance

Written after an intensive 2026-08-23 session: constraint inventory (1202
constraints, language tags classified), OpaqueBehavior audit, Jython-to-Groovy
conversion of two SAF validation rules (WUCASSCTX, WCTXAGGEL), model load/save
tooling, and direct rule execution against SAF_FFDS. Perspective: how does
this tool surface feel to an LLM that must operate it without a human in the
loop, and what does each unnecessary roundtrip cost?

## What worked (one line)

Hot reload of Groovy scripts (~2s, no restart), a strong read surface (typed
finders plus batch details), and `writableCheck` guards with readable errors
were the session's biggest enablers.

## Trial-and-error balance: four causes

Roughly 12 failed tool calls in that session trace to four causes:

| Cause | Cases | Examples |
|---|---|---|
| LLM prior-knowledge / version gap (deliberately not "API drift") | 5 | `closeProject(p, false)` does not exist; `loadProject(desc)` needs a boolean; `saveProject(project)` needs `(descriptor, boolean)`; descriptor creation wants a URI string, not a path |
| Missing generic introspection | 3 | guessing `ValidationRunData` constructors (17 signatures!); only ad-hoc introspection in the error path brought clarity |
| EMF metamodel details | 2 | no generated `OpaqueExpression.setLanguage(List)` setter (multi-valued feature without setter) -> `eSet(UMLPackage.Literals...)` required |
| Validation engine internals | 2+ | `DefaultValidationRuleImpl.run()` NPE (`filter` null); `ValidationHelper.validateElement` silently empty for script rules -> `rule_eval` GroovyShell fallback |

"API drift" is deliberately avoided: the Cameo API did not change during the
session; the gap is between the LLM's training priors (older releases,
tutorials) and the installed 2026x instance. Hallucinated convenience
signatures (e.g. the speculative `createLocalProjectDescriptor(File)`) are a
separate category. The EMF case is no version problem at all: multi-valued
EMF features never get generated setters.

## Knowledge priority: runtime & Javadoc over LLM priors

The failure cause is a knowledge problem, so verified sources must
systematically outrank LLM priors. Truth-source ranking (codified in
`AGENTS.md`, "Groovy code authoring (MCP surface navigation)"):

1. **Runtime JVM** (reflection on the live object) -- what *is*.
2. **Indexed Javadoc** (`md-javadoc-2026` via the `cameo-api` MCP server) -- what the installed version says *should* be.
3. **Session-verified cheat sheet** -- `docs/cameo-2026x-api-notes.md`; what already works in this environment.
4. **LLM priors** -- hypotheses only; never the basis of committed code.

### M-measures (one line each)

- **M1 -- "Javadoc-first" rule in AGENTS.md**: implemented, codified in `AGENTS.md` steps 1-5 (verify `com.nomagic.*` signatures via the `cameo-api_*` tools before writing code; absent from the index = treat as absent).
- **M2 -- FQN lint before deploy**: not implemented; no lint script exists in the repo, remains an open idea.
- **M3 -- generic introspection tool**: implemented, `plugincode_introspect` (originally `api_introspect`, renamed in the modelcode/plugincode namespace refactor; `scripts/plugincode.groovy`).
- **M4 -- Javadoc index with member signatures**: implemented; the `cameo-api` server indexes full method/constructor signatures (`lookup_symbol` with `#method(...)`, `get_members`).
- **M5 -- session cheat sheet**: implemented, `docs/cameo-2026x-api-notes.md`, consulted per `AGENTS.md` before writing `com.nomagic.*` FQNs.

Effect: M1 + M3 alone would have reduced every one of the five
prior-knowledge cases to a single roundtrip instead of two to three
(fail -> read error -> fix -> deploy -> test).

## Structural friction and P1 outcome

The review identified eight structural friction points: new tools invisible
in live sessions, container-vs-host path chaos, no server-side
filtering/pagination for bulk reads, tag reading available only as a "debug"
tool, inconsistent result shells, dirty-project close blocking on a modal
dialog, stereotype application as a `set_tagged_values` side effect, and the
active project as implicit context with no `projectId` parameter.

**P1 -- high value, small effort: ALL IMPLEMENTED and verified** in the
v0.2.2 script state (test suite 100/100): the generic `plugincode_introspect`
tool; first-class `get_stereotype_tags` plus `apply_stereotype` /
`remove_stereotype` with `writableCheck` guards; server-side
`find_elements_by_type(..., specLanguage=, specTextContains=)`; uniform find
result shells (id, name, qualifiedName, type, stereotypes, project); and a
host-path `hint` in `admin_load_model` / `admin_reset_model` file-not-found
errors.

The "new tools invisible in live sessions" weakness was a server weakness,
not a harness property -- **fixed and verified, see ADR-0015**
(`tools.listChanged` capability + SSE downstream channel + hot-reload
broadcast, including two sub-bugs: GET request bodies reach EOF immediately
so disconnect detection must not read the body, and
`GroovyScriptScanner.hasChanges()` previously never detected deletions).

Note on versioning: "v0.2.2" in this document denotes the MCP
`serverInfo.version` declared by the server (`McpProtocolHandler
SERVER_VERSION`), not a git tag -- git tags end at v0.1.6.

## Remaining work

All medium/long-term items from the original review (dirty-state handling,
`rule_eval` promotion, offset/limit batch reads, engine-integration NPE,
`projectId` parameter, unified error format, session GC) are tracked as
follow-up items in `plan.md` iteration 9, with session GC in iteration 8.

## Lessons learned

- The cost of a session is a knowledge gap, not a system gap: verified
  sources (JVM > Javadoc index > cheat sheet > priors) are the structural
  fix, and each must be one call away.
- Server-side gaps force client-side workarounds (curl helpers, manual
  chunking): close them server-side.
- Global state (active project, dirty flags, modals) is invisible from
  remote: surface it or provide an explicit escape hatch.
