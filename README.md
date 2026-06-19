# LLMChat

**LLMChat is a chat interface between a local LLM (running under [Ollama](https://ollama.com)) and the cloud-based [OwnSona](https://github.com/blakemcbride/Ownsona) MCP server.** It is built on the [Kiss](https://github.com/blakemcbride/Kiss) web development framework.

You talk to a model running entirely on your own machine, but its answers are grounded in your personal memory held by OwnSona — and anything the model learns about you during the conversation is written back to OwnSona.

## What it does

On each message, LLMChat:

1. **Recalls** relevant facts about you from the OwnSona MCP server.
2. **Builds a prompt** from those facts, the conversation so far, and your message.
3. **Generates** an answer with your local Ollama model.
4. **Displays** the answer.
5. **Learns** — durable new facts from the exchange are written back to OwnSona.

The local model never leaves your machine, and OwnSona holds the memory.

## Your local LLM can learn — and what it learns is shared everywhere

OwnSona is your own personal, user-controlled memory server. Because LLMChat writes everything the local model learns back into OwnSona, and because **OwnSona is also accessible to the major cloud assistants — ChatGPT, Claude, Grok, and Gemini** — anything your local LLM learns about you through LLMChat becomes available to all of them too.

In other words, a fact you teach your local model here is remembered once and shared everywhere: your local LLM and every connected cloud LLM draw on the same evolving personal memory.

## Two interfaces

LLMChat offers both:

- **Command line** — a terminal REPL (in the spirit of `ollama run`), with line editing, history, model selection, and streamed (token-by-token) replies.
- **Web** — a browser chat UI served by the embedded Kiss/Tomcat server.

Both interfaces share the same underlying engine, so they behave identically.

## Related projects

- **OwnSona** (the cloud memory MCP server): <https://github.com/blakemcbride/Ownsona>
- **Kiss** (the web development framework this is built on): <https://github.com/blakemcbride/Kiss> · [kissweb.org](https://kissweb.org)

## Requirements

- Java JDK 17+ (tested with 17/21/25)
- [Ollama](https://ollama.com) running locally with at least one model pulled
  (e.g. `ollama pull llama3.2`)
- Access to a running OwnSona MCP server (see the OwnSona project)
- An internet connection for the first build (to download dependencies) and to reach OwnSona

## Build

    ./bld build        # Linux, macOS, BSD   (use  bld build  on Windows)

This downloads dependencies and compiles the application.

## Configure

Settings live in `src/main/backend/application.ini`. The key values:

    OllamaUrl     = http://localhost:11434      ; local Ollama (no trailing /api/)
    OllamaModel   = llama3.2:latest             ; default model
    OwnSonaMcpUrl = https://ownsona.com/mcp     ; your OwnSona MCP endpoint

    [OAuthClient ownsona]
    Url = https://ownsona.com                   ; OwnSona origin (OAuth)

The first time you connect, LLMChat opens your browser once to log in to OwnSona;
the token is then cached and refreshed automatically.

## Run

### Command line (default)

    ./llmchat                 # start the chat REPL
    ./llmchat -m gemma3        # choose a model for this session
    ./llmchat --check          # print Ollama/OwnSona status and exit
    ./llmchat --help           # full usage

In the REPL, type a message to chat. Useful commands: `/model <name>`, `/models`,
`/memories` (show what was recalled), `/learn` (toggle auto-learning),
`/agent` (toggle agentic tool use), `/stream` (toggle streamed output), `/new`,
`/quit`. Say *"remember that …"* to store a fact explicitly.

### Web

    ./llmchat --web

Then open <http://localhost:8000> in your browser.

## Documentation

Design and implementation notes are in [LLMChat-Plan.md](LLMChat-Plan.md).

## Credits

LLMChat was written by Blake McBride. It is built on the
[Kiss](https://github.com/blakemcbride/Kiss) framework and connects to the
[OwnSona](https://github.com/blakemcbride/Ownsona) MCP server, both also by
Blake McBride.
