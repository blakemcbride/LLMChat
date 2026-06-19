package llmchat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.kissweb.oauth.client.OAuthAuthorizationRequiredException;
import org.kissweb.oauth.client.OAuthClient;
import org.kissweb.oauth.client.OAuthClientConfig;
import org.kissweb.oauth.client.PendingAuthorization;
import org.kissweb.restServer.MainServlet;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Standalone command-line interface for LLMChat &mdash; a terminal REPL (in the
 * spirit of <code>ollama run</code>) that augments a local Ollama model with the
 * user's OwnSona personal memory.  No web server is involved.
 * <br><br>
 * Each turn: recall relevant memories from OwnSona, build an <code>/api/chat</code>
 * messages array, generate with the local Ollama model, print the reply, and
 * write back any explicitly-requested fact ("remember that ...").
 * <br><br>
 * The framework runtime is reconstructed outside Tomcat by setting the
 * application path (so config and the shared <code>oauth.db</code> token store
 * resolve to the backend directory) and reading <code>application.ini</code>.
 * The OAuth authorization-code flow is completed by a transient built-in HTTP
 * listener that captures the browser redirect in-process.
 *
 * <h2>Usage</h2>
 * <pre>
 *   llmchat [backendDir]            # start the REPL
 *   llmchat [backendDir] --check    # print Ollama/OwnSona status and exit
 * </pre>
 * The launcher script passes the absolute backend directory as the first arg.
 */
public final class Cli {

    private static final String PROVIDER = "ownsona";

    private Cli() {
    }

    public static void main(String[] args) throws Exception {
        final PrintStream out = System.out;

        // Separate the backend-dir positional arg from flags.
        String backendDir = "src/main/backend";
        boolean checkOnly = false;
        String modelOverride = null;
        Boolean agenticFlag = null;            // null = use config default
        for (int i = 0; i < args.length; i++) {
            final String a = args[i];
            if ("--help".equals(a) || "-h".equals(a)) {
                printUsage(out);
                return;
            } else if ("--check".equals(a)) {
                checkOnly = true;
            } else if ("--model".equals(a) || "-m".equals(a)) {
                if (i + 1 >= args.length) {
                    out.println("Error: " + a + " requires a model name (e.g. -m llama3.2:latest).");
                    return;
                }
                modelOverride = args[++i];
            } else if ("--agent".equals(a)) {
                agenticFlag = Boolean.TRUE;
            } else if ("--no-agent".equals(a)) {
                agenticFlag = Boolean.FALSE;
            } else if (!a.startsWith("-")) {
                backendDir = a;
            }
            // unknown -flags are ignored
        }
        if (!backendDir.endsWith("/"))
            backendDir = backendDir + "/";

        // 1. Reconstruct the minimal Kiss runtime context.
        MainServlet.setApplicationPath(backendDir);
        MainServlet.readIniFile("application.ini", "main");

        final String model = (modelOverride != null && !modelOverride.isEmpty())
                ? modelOverride
                : env("OllamaModel", "llama3.2:latest");
        final String ollamaUrl = env("OllamaUrl", null);

        final OwnSonaClient os = new OwnSonaClient();
        final ChatEngine engine = new ChatEngine(os, ollamaUrl, model);
        engine.setAutoLearn(autoLearnEnabled());
        engine.setHistoryMaxMessages(envInt("HistoryMaxMessages", ChatEngine.DEFAULT_HISTORY_MAX_MESSAGES));
        engine.setAgentic(agenticFlag != null ? agenticFlag : agenticEnabled());

        if (checkOnly) {
            runCheck(out, engine, ollamaUrl);
            return;
        }

        out.println("LLMChat — memory-augmented chat (OwnSona + local Ollama)");
        out.println("Model: " + engine.getModel()
                + "    auto-learn: " + (engine.isAutoLearn() ? "on" : "off")
                + "    agent: " + (engine.isAgentic() ? "on" : "off")
                + "    Type /help for commands, /quit to exit.");

        if (!engine.ollamaUp())
            out.println("WARNING: local Ollama server not reachable"
                    + (ollamaUrl != null ? " at " + ollamaUrl : "") + ".");

        if (!ensureConnected(out))
            return;

        final List<Map<String, String>> history = new ArrayList<>();
        boolean showMemories = false;
        boolean streamOutput = streamEnabled();

        // JLine gives line editing, history (persisted to ~/.llmchat_history) and
        // up-arrow recall.  dumb(true) makes it degrade gracefully to plain line
        // reading when stdin isn't a terminal (e.g. piped input).
        final Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).name("llmchat").build();
        final LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, Paths.get(System.getProperty("user.home"), ".llmchat_history"))
                .build();
        while (true) {
            String raw;
            try {
                raw = reader.readLine("> ");
            } catch (UserInterruptException e) {     // Ctrl-C — abandon the current line
                continue;
            } catch (EndOfFileException e) {         // Ctrl-D / EOF
                break;
            }
            if (raw == null)
                break;
            final String line = raw.trim();
            if (line.isEmpty())
                continue;

            if (line.charAt(0) == '/') {
                final String lc = line.toLowerCase();
                if (lc.equals("/quit") || lc.equals("/exit") || lc.equals("/q"))
                    break;
                if (lc.equals("/help")) {
                    printHelp(out);
                    continue;
                }
                if (lc.equals("/new") || lc.equals("/reset")) {
                    history.clear();
                    out.println("(context cleared)");
                    continue;
                }
                if (lc.equals("/models")) {
                    printModels(engine, out);
                    continue;
                }
                if (lc.startsWith("/model ")) {
                    final String m = line.substring(7).trim();
                    if (!m.isEmpty()) {
                        engine.setModel(m);
                        out.println("(model = " + engine.getModel() + ")");
                    }
                    continue;
                }
                if (lc.equals("/memories")) {
                    showMemories = !showMemories;
                    out.println("(show recalled memories: " + (showMemories ? "on" : "off") + ")");
                    continue;
                }
                if (lc.equals("/learn")) {
                    engine.setAutoLearn(!engine.isAutoLearn());
                    out.println("(auto-learn: " + (engine.isAutoLearn() ? "on" : "off") + ")");
                    continue;
                }
                if (lc.equals("/agent")) {
                    engine.setAgentic(!engine.isAgentic());
                    out.println("(agent: " + (engine.isAgentic() ? "on" : "off") + ")");
                    continue;
                }
                if (lc.equals("/stream")) {
                    streamOutput = !streamOutput;
                    out.println("(stream: " + (streamOutput ? "on" : "off") + ")");
                    continue;
                }
                if (lc.equals("/connect")) {
                    ensureConnected(out);
                    continue;
                }
                out.println("Unknown command. /help for the list.");
                continue;
            }

            // Stream the reply token-by-token, except in agentic mode (tool rounds
            // aren't streamed).
            final boolean streaming = streamOutput && !engine.isAgentic();

            // Run the turn (auth-gated, with one reconnect attempt).
            ChatEngine.Turn turn;
            try {
                turn = runTurn(engine, line, history, out, streaming);
            } catch (OAuthAuthorizationRequiredException e) {
                out.println("OwnSona authorization required — reconnecting…");
                if (!ensureConnected(out))
                    continue;
                try {
                    turn = runTurn(engine, line, history, out, streaming);
                } catch (Exception e2) {
                    printTurnError(out, engine, e2);
                    continue;
                }
            } catch (Exception e) {
                printTurnError(out, engine, e);
                continue;
            }

            if (showMemories) {
                final int n = turn.usedMemories.size();
                out.println("[recalled " + n + (n == 1 ? " memory]" : " memories]"));
                for (Memory m : turn.usedMemories)
                    out.println(String.format("  - (%.2f) %s", m.score, m.text));
            }

            if (!streaming) {           // streaming already printed the answer as it arrived
                out.println();
                out.println(turn.answer);
            }

            history.add(ChatEngine.msg("user", line));
            history.add(ChatEngine.msg("assistant", turn.answer));
            // Bound in-memory history (the engine only sends the most recent anyway).
            while (history.size() > engine.getHistoryMaxMessages())
                history.remove(0);

            if (turn.remembered != null)
                out.println("(remembered)");
            else if (turn.rememberError != null)
                out.println("(could not remember: " + turn.rememberError + ")");
        }
        try {
            terminal.close();
        } catch (Exception ignore) {
            // best-effort
        }
        // Let any background auto-learning from the last turn(s) finish before exit.
        ChatEngine.drainAutoLearn(30);
        out.println("Bye.");
    }

    // ====================================================================================
    // OwnSona connection
    // ====================================================================================

    private static boolean ensureConnected(PrintStream out) throws Exception {
        OAuthClientConfig.reset();   // pick up application.ini edits without a restart
        final OAuthClient client = OAuthClient.forProvider(PROVIDER);
        if (client.isAuthorized()) {
            try {
                client.getAccessToken();   // forces a refresh if the access token expired
                out.println("OwnSona: connected.");
                return true;
            } catch (OAuthAuthorizationRequiredException ignore) {
                // fall through to interactive login
            }
        }
        return interactiveLogin(client, out);
    }

    /**
     * Run the authorization-code + PKCE flow, capturing the browser redirect with
     * a transient in-process HTTP listener (the same JVM that registered the
     * {@link PendingAuthorization}, so {@code consume(state)} resolves it).
     */
    private static boolean interactiveLogin(OAuthClient client, PrintStream out) throws Exception {
        final String base = env("OAuthClientRedirectBaseUrl", "http://localhost:8080");
        final URI baseUri = URI.create(base);
        final int port = baseUri.getPort() > 0 ? baseUri.getPort() : 8080;
        final String path = OAuthClientConfig.CALLBACK_PATH;   // /oauth/client/callback

        final BlockingQueue<String> result = new ArrayBlockingQueue<>(1);
        final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(path, (HttpExchange ex) -> {
            final Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String html;
            String status;
            final String error = q.get("error");
            if (error != null) {
                html = "<h2>Authorization error</h2><p>" + esc(error) + "</p>";
                status = "error:" + error;
            } else {
                final PendingAuthorization pending = PendingAuthorization.consume(q.get("state"));
                final String code = q.get("code");
                if (pending == null || code == null) {
                    html = "<h2>Authorization error</h2><p>Invalid state or missing code.</p>";
                    status = "error:invalid_state";
                } else {
                    try {
                        OAuthClient.forProvider(pending.getProvider()).completeAuthorization(pending, code);
                        html = "<h2>Connected</h2><p>You may close this window and return to the terminal.</p>";
                        status = "ok";
                    } catch (Exception e) {
                        html = "<h2>Authorization error</h2><p>" + esc(e.getMessage()) + "</p>";
                        status = "error:exchange";
                    }
                }
            }
            final byte[] body = ("<!DOCTYPE html><html><body>" + html + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
            result.offer(status);
        });
        server.start();
        try {
            final String url = client.beginAuthorization(base);
            out.println("\nTo connect to OwnSona, open this URL in your browser and log in:");
            out.println("  " + url);
            tryOpenBrowser(url);
            out.println("\nWaiting for authorization (Ctrl-C to abort)…");
            final String status = result.poll(5, TimeUnit.MINUTES);
            if (status == null) {
                out.println("Timed out waiting for authorization.");
                return false;
            }
            if (status.startsWith("error")) {
                out.println("Authorization failed: " + status);
                return false;
            }
            out.println("OwnSona: connected.");
            return true;
        } finally {
            server.stop(0);
        }
    }

    private static void tryOpenBrowser(String url) {
        try {
            final String osName = System.getProperty("os.name", "").toLowerCase();
            final ProcessBuilder pb = osName.contains("mac")
                    ? new ProcessBuilder("open", url)
                    : new ProcessBuilder("xdg-open", url);
            pb.inheritIO().start();
        } catch (Exception ignore) {
            // The user can copy/paste the printed URL manually.
        }
    }

    // ====================================================================================
    // Misc
    // ====================================================================================

    private static void runCheck(PrintStream out, ChatEngine engine, String ollamaUrl) {
        out.println("LLMChat --check");
        out.println("  application path : " + MainServlet.getApplicationPath());
        out.println("  Ollama URL       : " + (ollamaUrl != null ? ollamaUrl : "(default)"));
        out.println("  model            : " + engine.getModel());
        out.println("  Ollama reachable : " + engine.ollamaUp());
        out.println("  auto-learn       : " + (engine.isAutoLearn() ? "on" : "off"));
        out.println("  agent (tools)    : " + (engine.isAgentic() ? "on" : "off"));
        out.println("  stream replies   : " + (streamEnabled() ? "on" : "off"));
        out.println("  OwnSona MCP URL  : " + env("OwnSonaMcpUrl", "(unset)"));
        boolean authorized = false;
        try {
            OAuthClientConfig.reset();
            authorized = OAuthClient.forProvider(PROVIDER).isAuthorized();
        } catch (Exception e) {
            out.println("  OwnSona config   : ERROR " + e.getMessage());
        }
        out.println("  OwnSona token    : " + (authorized ? "present (authorized)" : "absent (login needed)"));
    }

    /** Whether auto-learning is enabled (the AutoLearn config key; default on). */
    private static boolean autoLearnEnabled() {
        final String v = env("AutoLearn", "true").trim();
        return !"false".equalsIgnoreCase(v) && !"off".equalsIgnoreCase(v) && !"0".equals(v);
    }

    /** Whether the agentic tool-calling loop is enabled (the Agentic config key; default off). */
    private static boolean agenticEnabled() {
        final String v = env("Agentic", "false").trim();
        return "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v) || "1".equals(v);
    }

    /** Whether CLI reply streaming is enabled (the Stream config key; default on). */
    private static boolean streamEnabled() {
        final String v = env("Stream", "true").trim();
        return !"false".equalsIgnoreCase(v) && !"off".equalsIgnoreCase(v) && !"0".equals(v);
    }

    /** Run one turn, streaming the reply to stdout when requested. */
    private static ChatEngine.Turn runTurn(ChatEngine engine, String line, List<Map<String, String>> history,
                                           PrintStream out, boolean streaming) throws Exception {
        if (!streaming)
            return engine.respond(line, history);
        out.println();   // blank line before the streamed reply
        final ChatEngine.Turn turn = engine.respondStreaming(line, history, chunk -> {
            out.print(chunk);
            out.flush();
        });
        out.println();   // end the streamed line
        return turn;
    }

    private static void printUsage(PrintStream out) {
        out.println("LLMChat — memory-augmented terminal chat (OwnSona + local Ollama)");
        out.println();
        out.println("Usage: llmchat [options]");
        out.println();
        out.println("Options:");
        out.println("  -h, --help          show this help and exit");
        out.println("  -m, --model <name>  Ollama model to use (overrides the configured default)");
        out.println("      --agent         enable the agentic tool-calling loop (needs a tool-capable model)");
        out.println("      --no-agent      disable the agentic loop (use the fixed pipeline)");
        out.println("      --check         print Ollama/OwnSona status and exit");
        out.println("      --cli           force the terminal interface (the default)");
        out.println("      --web           start the web interface instead (Kiss server + browser UI)");
        out.println();
        out.println("Run with no options to start the interactive chat REPL.");
        out.println("Each message recalls relevant facts from your OwnSona personal memory,");
        out.println("runs them through the local Ollama model, and prints a grounded reply.");
        out.println();
        out.println("REPL commands:");
        out.println("  /help            show the in-REPL command list");
        out.println("  /new, /reset     clear the conversation context");
        out.println("  /models          list installed Ollama models");
        out.println("  /model <name>    switch the Ollama model");
        out.println("  /memories        toggle showing the memories recalled per turn");
        out.println("  /learn           toggle auto-learning (extracting facts to OwnSona)");
        out.println("  /agent           toggle the agentic tool-calling loop");
        out.println("  /stream          toggle streaming the reply as it is generated");
        out.println("  /connect         (re)connect to OwnSona");
        out.println("  /quit, /exit     leave");
        out.println();
        out.println("Tip: say \"remember that ...\" to store a fact in OwnSona. Auto-learning also");
        out.println("saves durable facts it infers from the conversation (toggle with /learn).");
    }

    private static void printHelp(PrintStream out) {
        out.println("Commands:");
        out.println("  /help            show this help");
        out.println("  /new, /reset     clear the conversation context");
        out.println("  /models          list installed Ollama models");
        out.println("  /model <name>    switch the Ollama model");
        out.println("  /memories        toggle showing the memories recalled per turn");
        out.println("  /learn           toggle auto-learning (extracting facts to OwnSona)");
        out.println("  /agent           toggle the agentic tool-calling loop");
        out.println("  /stream          toggle streaming the reply as it is generated");
        out.println("  /connect         (re)connect to OwnSona");
        out.println("  /quit, /exit     leave");
        out.println("Tip: say \"remember that ...\" to store a fact in OwnSona.");
    }

    private static void printModels(ChatEngine engine, PrintStream out) {
        try {
            final List<String> models = engine.availableModels();
            if (models.isEmpty()) {
                out.println("(no models installed)");
                return;
            }
            for (String m : models)
                out.println("  " + m);
        } catch (Exception e) {
            out.println("Could not list models: " + e.getMessage());
        }
    }

    private static String env(String key, String dflt) {
        final Object v = MainServlet.getEnvironment(key);
        return v == null ? dflt : v.toString();
    }

    private static int envInt(String key, int dflt) {
        final String v = env(key, null);
        if (v == null)
            return dflt;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Print a turn error, adding a hint if the local Ollama server looks down. */
    private static void printTurnError(PrintStream out, ChatEngine engine, Exception e) {
        out.println("Error: " + e.getMessage());
        try {
            if (!engine.ollamaUp())
                out.println("  (the local Ollama server looks unreachable — is it running?)");
        } catch (Exception ignore) {
            // hint is best-effort
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        final Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty())
            return map;
        for (String pair : raw.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq < 0)
                continue;
            final String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            final String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    private static String esc(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
