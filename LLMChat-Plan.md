# LLMChat — Design & Implementation

*A KISS-framework application that bridges the cloud **OwnSona** MCP memory
server with a **local Ollama** LLM: answers are grounded in the user's personal
memory, and newly-learned facts are written back to OwnSona. Usable as a
**standalone terminal CLI** or a **web UI**, both driving one shared engine.*

Last updated: 2026-06-18 (rev 8 — Phase 6 CLI streaming done; web streaming (SSE) still optional)

---

## 0. Current state (what actually exists)

**Implemented and verified end-to-end.** A turn recalls memories from
`https://ownsona.com/mcp`, builds an `/api/chat` messages array, generates with a
local Ollama model, prints/returns the answer, writes back explicit "remember
that …" facts, **and** runs a background **auto-learning** pass that extracts
durable facts from the exchange and saves them too. Tested both: an explicit
`remember that …` and a conversationally-stated fact are each stored and recalled
in a later session.

Two interchangeable front-ends over one shared core (`llmchat.ChatEngine`):

- **CLI (primary):** `./llmchat` — a standalone JVM REPL, **no web server**.
- **Web:** `./llmchat --web` — starts the Kiss/Tomcat server; the browser opens
  **directly on the chat** (no login, no demo menu) at `http://localhost:8000`.

Done: Phases 0–5 (connectivity, OwnSona client, chat MVP, explicit write-back,
**3-v2 auto-learning**, **4 polish** — history capping, web memory-transparency,
clearer errors, **5 agentic tool-calling loop** — opt-in), the dual-mode/shared-engine
refactor, the standalone CLI (with **JLine** line-editing/history and **token
streaming**), and the login-free web.
Not yet done: web streaming (SSE) — optional; the CLI side of Phase 6 is done.

---

## 1. Goal & scope

LLMChat is the user-facing front end to a private, memory-augmented chat loop:

1. User types a message (terminal CLI **or** web UI).
2. **Recall** relevant facts from the cloud OwnSona MCP server.
3. **Construct a prompt** (system instructions + recalled facts + history + message).
4. Feed it to the **local Ollama** server and get an answer.
5. **Display** the answer (and, optionally, which memories grounded it).
6. **Write new durable facts back** to OwnSona so memory grows over time — both
   explicit ("remember that …") and auto-learned (inferred from the exchange).

Nothing here changes OwnSona — LLMChat is purely an OAuth **client** of OwnSona's
`/mcp` endpoint and a **client** of the local Ollama HTTP API. Both framework
building blocks now exist too: `org.kissweb.MCPClientBase` (MCP client base,
mirror of `MCPServerBase`) and `Ollama.chat()` (multi-turn `/api/chat`, with a
`tools` overload for the agentic path). So the project was pure assembly — no new
framework infrastructure.

---

## 2. Architecture

### 2.1 Shared engine, two front-ends

The heavy lifting is UI-agnostic and lives in the `llmchat` package
(`src/main/precompiled/`): `OwnSonaClient` (memory + OAuth), `Memory`/`NewFact`
POJOs, prompt construction, write-back — all orchestrated by **`ChatEngine`**.
Each front-end is thin glue over `ChatEngine.respond(message, history)`:

```
                         ┌─────────────────────────┐
   terminal  ── Cli.java ─┤                         ├─ OwnSonaClient ─▶ ownsona.com/mcp
                          │     ChatEngine          │   (MCPClientBase + OAuth)
   browser ── ChatService ┤  recall→prompt→chat→    │
            (Groovy, web) │     writeback           ├─ Ollama ───────▶ localhost:11434
                         └─────────────────────────┘
```

Because both modes call the same `ChatEngine`, behavior cannot drift between them.
The modes are different **runtimes**, not one process toggled by a flag:

- **CLI mode** = a plain `java llmchat.Cli` process, no Tomcat. OAuth's redirect is
  captured by a transient in-process `com.sun.net.httpserver.HttpServer`.
- **Web mode** = the Kiss/Tomcat server. OAuth's redirect is captured by the
  framework's `/oauth/client/callback` servlet.

A single dispatcher (`./llmchat`, with `--cli`/`--web`) picks the runtime — the
`ollama run` vs `ollama serve` shape.

### 2.2 Refinements baked in

- **No embeddings on our side.** OwnSona embeds server-side (OpenAI
  `text-embedding-3-small`, 1536-dim, pgvector). We send *text* to
  `recall`/`remember`; we never use `Ollama.getEmbeddings()` or Qdrant.
- **We construct the prompt** (via `recall`) rather than OwnSona's
  `build_context_prompt`, for control over the system prompt.
- **Dedup-aware write-back:** `dedup_policy = skip_if_near`; `capture_mode =
  explicit` for user-directed "remember that …", `inferred` for auto-extracted.

### 2.3 The one real architectural decision: fixed pipeline vs. agentic loop

- **A. Fixed pipeline (default).** Deterministic: `recall → prompt → generate →
  explicit/auto write-back`. Reliable, model-agnostic, works with any Ollama model.
- **B. Agentic tool-calling loop (implemented, opt-in — see §8.2).** OwnSona's tools
  are advertised to the model, which decides when to recall/remember/update/forget;
  results feed back until it answers. More powerful (handles corrections +
  "forget that"), but needs a tool-calling-capable model. The same `OwnSonaClient`
  serves both; only `ChatEngine` branches. Off by default (`Agentic = false`);
  enable via config, the CLI `--agent`/`/agent`, or per-request.

---

## 3. What the framework provides (so we don't reinvent it)

| Capability | Class | Notes |
|---|---|---|
| Local LLM | `llm/Ollama.java` | `chat(List<Map<String,String>>)` + `chat(messages, JSONArray tools)` (multi-turn `/api/chat`; the `tools` overload is the agentic foundation); also `send()`, `getAvailableModels()`, `isOllamaUp()`, `selectModel()`. |
| MCP client base | `MCPClientBase.java` | Mirror of `MCPServerBase`: JSON-RPC 2.0, lazy `initialize`, `tools/list`, `tools/call`, OAuth bearer auth + transparent refresh, content-block unwrapping (`callToolText`/`textOf`/`isError`). Subclass implements `getServerUrl()` + optional `getOAuthProviderName()`. |
| Outbound OAuth 2.1 | `oauth/client/OAuthClient.java` | `forProvider()`, `isAuthorized()`, `beginAuthorization()`, `getAccessToken()` (auto-refresh, PKCE, DCR). Sends the RFC 8707 `resource` parameter as the configured provider `Url` (see §9 audience note). Config cached for JVM life — `OAuthClientConfig.reset()` re-reads it. |
| OAuth callback (web) | `oauth/client/OAuthCallbackServlet` | `/oauth/client/callback`; consumes the in-memory `PendingAuthorization`. The CLI replicates this with its own listener. |
| HTTP / JSON-RPC | `RestClient.java` | Used by `MCPClientBase`. |
| Runtime context | `MainServlet` | `setApplicationPath()` + `readIniFile()` reconstruct config/env **outside a servlet** (the CLI relies on this). `OAuthSqliteStore` resolves `getApplicationPath() + "oauth.db"`. |

### OwnSona server (read-only reference at `/home/blake/GitHub.blakemcbride/Ownsona`)
- Endpoint **`POST https://ownsona.com/mcp`**, JSON-RPC 2.0, protocol `2025-06-18`.
- Auth: OAuth 2.1 bearer JWT, embedded AS with PKCE + Dynamic Client Registration.
- **Resource identifier is the bare origin `https://ownsona.com`** — the token
  `aud` must equal this (see §9).
- Tools used: `recall`, `remember`, `remember_batch`, `update_memory`, `forget`
  (21 total available). Single-user deployment.

---

## 4. App components (`src/main/precompiled/llmchat/`)

Java, compiled by `./bld build` (precompiled classes are **not** hot-reloaded).

- **`OwnSonaClient extends MCPClientBase`** — typed wrappers (`recall`, `remember`,
  `rememberBatch`, `updateMemory`, `forget`) over `callToolText`. Overrides
  `getServerUrl()` → `MainServlet.getEnvironment("OwnSonaMcpUrl")` and
  `getOAuthProviderName()` → `"ownsona"`. Auth/refresh/transport all inherited.
- **`Memory`** / **`NewFact`** — small POJOs parsed from OwnSona tool results.
- **`ChatEngine`** — the shared loop. `respond(message, history)` returns
  `Turn { answer, usedMemories, remembered, rememberError }`. Owns Ollama-client
  creation (one fresh client per call → concurrent-safe). Runs the **auto-learning**
  extraction on a background daemon thread (single-thread pool); `setAutoLearn()`
  toggles it; `drainAutoLearn(seconds)` lets the CLI flush pending learning at
  exit. Statics `buildMessages()`, `msg()`, `extractExplicitRemember()`,
  `parseFacts()`. No UI concerns.
- **`Cli`** — standalone terminal front-end (see §7).

---

## 5. Configuration (`src/main/backend/application.ini`)

```ini
[main]
# Local Ollama — base URL, NO trailing /api/ (the Ollama client appends it)
OllamaUrl   = http://localhost:11434
OllamaModel = llama3.2:latest        ; default model; CLI /model or web dropdown overrides
AutoLearn   = true                   ; background fact-extraction write-back (CLI /learn toggles it)

# OwnSona MCP endpoint — read by OwnSonaClient.getServerUrl()
OwnSonaMcpUrl = https://ownsona.com/mcp

# Base for the OAuth redirect_uri (<base>/oauth/client/callback)
OAuthClientRedirectBaseUrl = http://localhost:8080

# OAuth client — Url is the BARE ORIGIN (no /mcp): it is the RFC 8707 resource
# parameter and becomes the token's aud, which OwnSona requires to be the origin.
[OAuthClient ownsona]
Url          = https://ownsona.com
Scopes       =
ClientId     =                       ; blank => Dynamic Client Registration
ClientSecret =
```

Note the deliberate split: `OwnSonaMcpUrl` (`…/mcp`, the API endpoint) is separate
from `[OAuthClient ownsona] Url` (`…`, the OAuth audience/origin). See §9.

---

## 6. Web front-end (`--web` mode)

**Login-free and chat-only.** The Kiss sample (login + demo screens/services) was
removed; `index.js` opens straight to `screens/MemoryChat`, and `KissInit.groovy`
marks the three chat services `allowWithoutAuthentication` (localhost personal
tool; OwnSona's OAuth is the real gate). Thin Groovy JSON-RPC services
(hot-reloadable) over the shared engine, plus the `MemoryChat` screen:

- **`ChatService.send`** — adapts `{message, model, history}` → `ChatEngine`
  (`setAutoLearn` from the `AutoLearn` config), returns `{answer, htmlAnswer,
  usedMemories, remembered}`; maps `OAuthAuthorizationRequiredException` →
  `{needsLogin}` and other errors → `{error}`.
- **`OwnSonaAuth`** — `status` / `beginLogin` (returns the browser URL;
  `OAuthClientConfig.reset()` + `clearTokens()` first) / `logout`.
- **`OllamaInfo`** — `health`, `models` (`defaultModel` honors the `-m` override,
  passed in via the `LLMCHAT_MODEL` env var — see §7).
- **`screens/MemoryChat/`** — centered chat transcript, model dropdown, OwnSona
  connect/disconnect toggle badge. KISS components only (renders via
  `text-label.setHTMLValue`, no direct DOM).

Streaming to the browser is not provided by KISS (`Server.call` is req/resp);
deferred to Phase 6 (an SSE servlet + `RestClient.streamCall`).

---

## 7. CLI front-end (`--cli` / default)

**`llmchat.Cli`** — a terminal REPL, no web server. Key points:

- **Runtime reconstruction:** `MainServlet.setApplicationPath(backendDir)` +
  `readIniFile()` so config/env and the shared `oauth.db` resolve to the backend
  dir. The launcher passes the absolute backend path.
- **OAuth:** reuses the token in `oauth.db` (refreshes silently). For
  first-time/expired auth it starts a transient `HttpServer` on the redirect
  port, opens the browser, and completes the flow via
  `PendingAuthorization.consume(state)` → `completeAuthorization` in-process.
- **REPL:** `respond()` per line; commands `/help`, `/new`, `/models`,
  `/model <name>`, `/memories` (show recalled), `/learn` (toggle auto-learning),
  `/connect`, `/quit`. On exit it calls `ChatEngine.drainAutoLearn(30)` so a fact
  from the last turn still saves. Flags: `-m`/`--model <name>` (startup model
  override), `--check` (status + exit), `-h`/`--help` (usage).
- **Line editing:** JLine (`org.jline:jline` bundle) provides line editing,
  up-arrow history persisted to `~/.llmchat_history`, and graceful degradation to
  plain line reading when stdin isn't a TTY (`TerminalBuilder.dumb(true)` — so
  piped input still works). `UserInterruptException` (Ctrl-C) abandons the line;
  `EndOfFileException` (Ctrl-D) exits.
- **Streaming:** replies print token-by-token via `ChatEngine.respondStreaming()`
  (drives `/api/chat` with `stream:true`, reading newline-delimited JSON chunks
  through `RestClient.streamCall` + `BodyHandlers.ofLines()`, feeding each chunk to
  a `Consumer`). On by default (`Stream` config / `/stream` toggle); skipped in
  agentic mode (tool rounds aren't streamed). Web stays request/response (would need
  an SSE endpoint — see Phase 6).
- **Quiet logging:** `cli-log4j2.xml` (status=WARN, ERROR→stderr) keeps the REPL
  clean on stdout.

**Launcher / dispatcher (`./llmchat`):** `--web` → `exec ./bld develop` (and, when
`-m` was given, `exec env LLMCHAT_MODEL=<model> ./bld develop` so the web honors
the override); otherwise runs `java -cp <classes:libs> llmchat.Cli <backend>
[args]`, forwarding `-m`/`--check`/`--help`.

---

## 8. Prompt construction (`ChatEngine.buildMessages`)

A `List<Map<String,String>>` for `Ollama.chat()` — structured roles, so the
model's own chat template formats it:

- `system` message: instructions + recalled facts (rebuilt each turn, since
  `recall` is query-dependent).
- prior `history` turns as real `user`/`assistant` messages.
- the new `user` message.

Context budget: recalled facts are bounded (`RECALL_LIMIT = 8`), and history is
**capped** by `ChatEngine.trimHistory()` — the most recent messages within
`HistoryMaxMessages` (config; default 20) and a ~12k-char budget, dropping older
turns and never starting on an assistant turn. Both front-ends also bound their
stored history. Write-back is what lets old turns be dropped safely — durable
facts persist in OwnSona and are re-recalled rather than carried in the transcript.
Agentic path (Phase 5) feeds the same assembly to `chat(messages, tools)`.

### 8.1 Auto-learning (Phase 3-v2)

After producing the reply, `ChatEngine` dispatches a background task (single-thread
daemon pool) that asks the model — via a second `chat()` on its **own** Ollama
client — to extract durable, third-person facts about the user from the exchange,
as a JSON array of strings. `parseFacts()` is tolerant (takes the outermost
`[...]`, keeps only string elements), and any survivors are stored with
`rememberBatch(capture_mode=inferred, dedup_policy=skip_if_near)`. It never blocks
the reply and never throws into the chat (failures are logged and swallowed).
Toggle with the `AutoLearn` config / CLI `/learn`. The CLI drains the pool at exit
so last-turn facts persist; the long-lived web JVM completes them naturally.

### 8.2 Agentic tool-calling loop (Phase 5)

When `Agentic` is on, `ChatEngine.respondAgentic()` advertises a curated subset of
OwnSona's tools (`recall`, `remember`, `update_memory`, `forget`, `list_memories`,
`text_search`) to the model and loops: call `/api/chat` with `tools`, execute any
`tool_calls` via `OwnSonaClient.callTool()`, append the assistant turn + `role:"tool"`
results, repeat until a final answer (bounded by `AGENT_MAX_ROUNDS = 6`). The model
itself manages memory, so the explicit/auto write-back paths are skipped. Tool
descriptors are converted from `listTools()` (MCP `inputSchema` → function
`parameters`) and cached. `recall` results are also collected into the turn's
`usedMemories` for transparency.

**Why it bypasses `Ollama.chat()`:** the loop must round-trip the assistant's
`tool_calls` and `role:"tool"` results, which the string-only `Map<String,String>`
message form can't represent — so the agentic path drives `/api/chat` directly via
`RestClient` (the same transport `Ollama.chat` uses), entirely in app code (no
`core/` change). Off by default; enable via `Agentic = true`, the CLI
`--agent`/`--no-agent` flags, or the `/agent` runtime toggle. Needs a tool-capable
model (verified with `llama3.2`: it autonomously called `recall` to ground an answer
and `remember` to save a stated fact, rephrased to third person).

---

## 9. Lessons / gotchas already resolved

- **Token audience (the 401 we hit).** `OAuthClient` sends the RFC 8707
  `resource` = the provider `Url`, which becomes the token `aud`. OwnSona's
  resource server requires `aud = https://ownsona.com` (origin). Fix: set
  `[OAuthClient ownsona] Url = https://ownsona.com` (origin, **not** `…/mcp`),
  kept separate from `OwnSonaMcpUrl`. *(Framework idea for later: prefer the
  `resource` advertised in the RFC 9728 protected-resource metadata over the
  configured Url.)*
- **Config caching.** `OAuthClientConfig` is cached for the JVM; edits to
  `[OAuthClient …]` need `OAuthClientConfig.reset()` (the connect paths call it)
  or a restart.
- **Error labeling.** Don't treat every `MCPClientException` as "needs login" —
  only `OAuthAuthorizationRequiredException` should drive a login; log and surface
  the rest (that's how the audience 401 was caught).
- **Ollama URL format.** `Ollama(url, model)` appends `/api/`, so `OllamaUrl`
  must be the base (`http://localhost:11434`), not `…/api/`.
- **`getEnvironment()` returns `Object`** — cast/`toString()` in Java (Groovy
  coerces).
- **Default model must be installed** — `llama3.1` wasn't; default is now
  `llama3.2:latest`.
- **Port 8080** is used by both the web server and the CLI's first-time OAuth
  listener; don't run a `--web` server and a *fresh* CLI login simultaneously.
- **Auto-learn concurrency.** `Ollama.chat()` stores its `RestClient` in an
  instance field, so one `Ollama` is **not** safe for concurrent calls — the
  background extraction must use its own client. `ChatEngine` therefore mints a
  fresh `Ollama` per call rather than sharing one.
- **Daemon thread + exit.** The auto-learn pool is daemon (won't hang exit), but a
  short CLI session could exit before it runs — hence `drainAutoLearn()` on `/quit`.
- **`-m` in web mode** flows via the `LLMCHAT_MODEL` env var (the launcher sets it
  before `./bld develop`); the backend prefers it over `OllamaModel`. Starting the
  server any other way falls back to the config default.
- **No-login web** relies on `allowWithoutAuthentication` for the chat services; if
  the web is ever exposed beyond localhost, restore a login.
- **Agentic transport.** `Ollama.chat()` returns only the reply text and its
  `Map<String,String>` messages can't carry `tool_calls`/`role:"tool"` turns, so the
  agentic loop drives `/api/chat` directly via `RestClient` (app code, no `core/`
  change). Ollama returns tool-call `arguments` as a JSON object (not a string) —
  `toArgs()` handles both.

---

## 10. Remaining work

| Phase | Deliverable |
|---|---|
| **6 — Web streaming (optional)** | SSE servlet + `RestClient.streamCall` → token-by-token *web* UI. (CLI streaming is done.) |

Possible agentic follow-ups (optional): cache `listTools()` per process (done) but
add a refresh; let the web UI toggle agent mode; widen/curate the tool allowlist.

Done since rev 3: **3-v2 auto-learning** (§8.1); **Phase 4 polish** — history
capping (`HistoryMaxMessages`), web memory-transparency, clearer errors; **Phase 5
agentic loop** (§8.2, `Agentic`/`--agent`/`/agent`); **JLine** CLI line-editing/
history; **CLI token streaming** (`Stream`/`/stream`); the web strip-down (login-free,
chat-only, centered); CLI `-m`/`--model` (+ web env override), `/learn`, `/agent`.

Watch: over-remembering (lean on `skip_if_near` + periodic `find_near_duplicates`);
OwnSona's `SecretScanner` rejects credential-shaped `remember` text (the extraction
pass should avoid sending such strings).

---

## 11. File manifest

Framework (already in place, by Blake):
```
src/main/core/org/kissweb/MCPClientBase.java          generic MCP client base
src/main/core/org/kissweb/llm/Ollama.java             chat(messages) + chat(messages, tools)
```
App code (this project):
```
src/main/precompiled/llmchat/ChatEngine.java          shared loop + auto-learning + agentic tool-loop
src/main/precompiled/llmchat/OwnSonaClient.java        extends MCPClientBase (typed tool wrappers)
src/main/precompiled/llmchat/Memory.java               POJO
src/main/precompiled/llmchat/NewFact.java              POJO (write-back / batch)
src/main/precompiled/llmchat/Cli.java                  standalone terminal REPL (JLine line editing/history)
src/main/precompiled/Tasks.java                        build: registers the jline jar (foreign dependency)
libs/jline-3.26.3.jar                                  JLine bundle (downloaded by ./bld libs)
llmchat                                                launcher / dispatcher (--cli | --web; -m forwarding)
cli-log4j2.xml                                         quiet logging for the CLI
src/main/backend/application.ini                       Ollama*, AutoLearn, HistoryMaxMessages, Agentic, Stream, OwnSonaMcpUrl, [OAuthClient ownsona]
src/main/backend/KissInit.groovy                       allowWithoutAuthentication for the chat services
src/main/backend/services/ChatService.groovy           web: thin over ChatEngine
src/main/backend/services/OwnSonaAuth.groovy           web: OAuth connect lifecycle
src/main/backend/services/OllamaInfo.groovy            web: health + model list (+ LLMCHAT_MODEL override)
src/main/frontend/index.js                             opens directly on MemoryChat (no login)
src/main/frontend/screens/MemoryChat/MemoryChat.{html,js}   web chat screen (centered)
```

Removed (Kiss sample / login): `login.{html,js}`, `Login.groovy`, `mobile/`, and
the demo screens + services (Framework, Controls, CRUD, Users, RestServices,
FileUpload, Ollama, Report, Export, SQLAccess, etc.).

Build: `./bld build` (compiles `precompiled/`; required for the CLI). Groovy
services under `backend/` and frontend files reload live in `--web` mode (a
`KissInit.groovy` change needs a server restart).
Run: `./llmchat` (terminal) · `./llmchat --web` (browser) · `./llmchat --check`.
