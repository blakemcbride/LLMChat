# LLMChat — Installation & Usage

LLMChat is a chat interface between a **local LLM** (running under [Ollama](https://ollama.com))
and the cloud-based [**OwnSona**](https://github.com/blakemcbride/Ownsona) personal-memory
MCP server. It is built on the [**Kiss**](https://github.com/blakemcbride/Kiss) web framework
and offers both a **command-line** and a **web** interface.

This document covers everything needed to install, configure, and run it.

---

## 1. Prerequisites

| Requirement | Notes |
|---|---|
| **Java JDK 17+** | Tested with 17, 21, and 25. Ensure `java` and `javac` are on your `PATH`. Set `JAVA_HOME` (and `JRE_HOME` if your tooling expects it). |
| **Git** | To clone the repository. |
| **Internet connection** | Needed for the first build (downloads dependency jars) and to reach the OwnSona server. |
| **[Ollama](https://ollama.com)** | Must be installed and running locally, with at least one model pulled. |
| **An OwnSona MCP server** | A running OwnSona instance you can log in to. See <https://github.com/blakemcbride/Ownsona>. |

### Install and prepare Ollama

1. Install Ollama from <https://ollama.com>.
2. Make sure the Ollama server is running (it usually starts automatically; otherwise run `ollama serve`).
3. Pull at least one model, for example:

       ollama pull llama3.2

   A **tool-calling-capable** model (such as the Llama 3.x family) is recommended if you want to
   use the optional *agentic* mode (§6).

By default Ollama listens on `http://localhost:11434`.

---

## 2. Get the code and build

    git clone https://github.com/blakemcbride/LLMChat.git
    cd LLMChat

Build it (downloads dependencies and compiles everything):

**Linux, macOS, BSD:**

    ./bld build

**Windows:**

    bld build

The first build downloads the required jars into `libs/`. Subsequent builds are fast.

> You must run `./bld build` (which compiles the `precompiled/` Java) before using the
> command-line interface. The web interface is started with `./bld develop` (wrapped by
> `./llmchat --web`, see §5), which builds as needed.

---

## 3. Configure

All settings live in **`src/main/backend/application.ini`**. The values you are most likely to
change are below.

### Ollama (local LLM)

    OllamaUrl   = http://localhost:11434     ; base URL — do NOT add a trailing /api/
    OllamaModel = llama3.2:latest            ; default model (must be installed in Ollama)

> The model name must match one shown by `ollama list` (or LLMChat's `/models` command). If the
> configured model isn't installed, generation will fail — change `OllamaModel` or pass `-m`.

### OwnSona (cloud memory server)

    OwnSonaMcpUrl = https://ownsona.com/mcp  ; your OwnSona MCP endpoint (the API URL)

    OAuthClientRedirectBaseUrl = http://localhost:8080   ; OAuth redirect base

    [OAuthClient ownsona]
    Url          = https://ownsona.com       ; the OwnSona ORIGIN (no /mcp path)
    Scopes       =                           ; OwnSona requires none by default
    ClientId     =                           ; blank => Dynamic Client Registration
    ClientSecret =

> **Important:** `OwnSonaMcpUrl` is the API endpoint (ends in `/mcp`), while
> `[OAuthClient ownsona] Url` is the bare **origin** (no `/mcp`). They are intentionally
> different: the origin becomes the OAuth token's audience, which OwnSona's resource server
> requires. Point both at your actual OwnSona host.

### Optional behavior settings

    AutoLearn          = true        ; learn durable facts from conversations (see §6)
    HistoryMaxMessages = 20          ; recent messages kept in context each turn
    Agentic            = false       ; let the model drive memory tools itself (see §6)
    Stream             = true        ; CLI: stream replies token-by-token (web is always non-streaming)

---

## 4. First-time connection to OwnSona

The first time LLMChat talks to OwnSona it must obtain an OAuth access token. This requires a
**one-time browser login**:

- **CLI:** the first time you send a message (or run `/connect`), LLMChat prints a URL and opens
  your browser. Log in to OwnSona and approve access; control returns to the terminal.
- **Web:** click **Connect OwnSona** on the chat screen; a tab opens to log in.

The token is then cached in `src/main/backend/oauth.db` and refreshed automatically — you will
not need to log in again unless you disconnect or the refresh token expires.

The OAuth redirect is captured on **port 8080**. Don't run a `--web` server and a *first-time*
CLI login at the same moment (they'd both want 8080). Once a token exists, this is a non-issue.

---

## 5. Running LLMChat

### Command line (default)

    ./llmchat                 # start the interactive chat REPL
    ./llmchat -m gemma3        # choose a model for this session (overrides OllamaModel)
    ./llmchat --agent          # start with agentic tool-calling enabled
    ./llmchat --check          # print Ollama/OwnSona status and exit
    ./llmchat --help           # full usage

Type a message at the `>` prompt and press Enter. Up-arrow recalls previous lines (history is
saved to `~/.llmchat_history`). Ctrl-D (or `/quit`) exits.

**In-REPL commands:**

| Command | Action |
|---|---|
| `/help` | Show the command list |
| `/new`, `/reset` | Clear the conversation context |
| `/models` | List installed Ollama models |
| `/model <name>` | Switch the Ollama model |
| `/memories` | Toggle showing which memories were recalled each turn |
| `/learn` | Toggle auto-learning (writing inferred facts to OwnSona) |
| `/agent` | Toggle the agentic tool-calling loop |
| `/stream` | Toggle streaming the reply as it is generated |
| `/connect` | (Re)connect to OwnSona |
| `/quit`, `/exit` | Leave |

Say **"remember that …"** in a message to store a fact in OwnSona explicitly.

### Web interface

    ./llmchat --web

Then open <http://localhost:8000> in your browser. The page opens directly on the chat (there is
no login screen). Use the **Connect OwnSona** button for the one-time OwnSona login, pick a model
from the dropdown, and chat.

- Frontend: `http://localhost:8000`
- Backend: `http://localhost:8080`

`./llmchat --web -m <model>` starts the web server with `<model>` pre-selected.

Stop the web server with Ctrl-C in the terminal where you started it.

---

## 6. Features

- **Memory-grounded answers.** Every message first recalls relevant facts about you from OwnSona
  and includes them in the prompt, so the local model answers with personal context.
- **Learning (write-back).** Facts are written back to OwnSona two ways: explicitly when you say
  *"remember that …"*, and automatically (`AutoLearn`) via a background pass that extracts durable
  facts from the conversation. Because OwnSona is your shared personal memory, anything learned
  here also becomes available to cloud assistants (ChatGPT, Claude, Grok, Gemini) that connect to
  the same OwnSona.
- **Agentic mode (`Agentic` / `--agent` / `/agent`).** Instead of the fixed recall→answer
  pipeline, the model is given OwnSona's memory tools and decides for itself when to recall,
  remember, update, or forget. Requires a tool-calling-capable model.
- **Streaming (CLI).** Replies print token-by-token as they are generated (`Stream` / `/stream`).
  The web interface is request/response.

---

## 7. Updating and rebuilding

After pulling new changes or editing the command-line code (anything under `src/main/precompiled/`):

    ./bld build

Backend service files (`src/main/backend/`) and frontend files reload live in `--web` mode and do
not require a rebuild — **except** `KissInit.groovy`, which is read at startup (restart the web
server to pick up changes there).

Other useful build commands:

    ./bld clean        # remove build output
    ./bld realclean    # also remove downloaded dependency jars (re-downloaded on next build)
    ./bld javadoc      # generate backend API docs into work/javadoc/

---

## 8. Troubleshooting

**"local Ollama server not reachable" / generation errors**
Make sure Ollama is running (`ollama serve`) and reachable at `OllamaUrl`. Run `./llmchat --check`
to see status.

**Model errors / "model not found"**
The configured model isn't installed. Run `ollama list` (or `/models` in the REPL) to see what's
available, then `ollama pull <model>`, set `OllamaModel`, or pass `-m <model>`.

**OwnSona 401 / "Token aud does not include resource identifier"**
The `[OAuthClient ownsona] Url` must be the bare **origin** (e.g. `https://ownsona.com`), not the
`/mcp` endpoint. Fix it, then reconnect (`/connect` in the CLI, or the Connect button on the web),
which re-reads the config and re-issues the token.

**"You need to connect to OwnSona"**
You aren't authorized yet (or the token was cleared). Run `/connect` (CLI) or click **Connect
OwnSona** (web) and complete the browser login.

**Port 8080 already in use**
Either a web server is already running, or a stale process holds the port. Stop the other server
before a first-time CLI login (both use 8080 for the OAuth callback).

**Build can't find dependencies**
Ensure you have an internet connection for the first build; `./bld build` downloads jars into
`libs/`. If a download was interrupted, `./bld realclean` then `./bld build` re-fetches them.

---

## 9. Where things live

| Path | What it is |
|---|---|
| `src/main/backend/application.ini` | All configuration |
| `src/main/backend/oauth.db` | Cached OwnSona OAuth tokens (auto-managed) |
| `src/main/backend/DB.sqlite` | Application database (SQLite) |
| `src/main/precompiled/llmchat/` | Command-line app + shared engine (Java) |
| `src/main/backend/services/` | Web JSON-RPC services (Groovy) |
| `src/main/frontend/screens/MemoryChat/` | Web chat screen |
| `~/.llmchat_history` | CLI command history |
| `llmchat` | Launcher / dispatcher script |
| `LLMChat-Plan.md` | Design & implementation notes |

---

## 10. Related projects

- **OwnSona** (cloud personal-memory MCP server): <https://github.com/blakemcbride/Ownsona>
- **Kiss** (web development framework): <https://github.com/blakemcbride/Kiss> · [kissweb.org](https://kissweb.org)
- **Ollama** (local LLM runtime): <https://ollama.com>
