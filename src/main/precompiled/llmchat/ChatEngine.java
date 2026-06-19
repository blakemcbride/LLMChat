package llmchat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.MCPClientBase;
import org.kissweb.RestClient;
import org.kissweb.json.JSONArray;
import org.kissweb.json.JSONObject;
import org.kissweb.llm.Ollama;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The shared, UI-agnostic chat loop: recall relevant memories from OwnSona,
 * build the <code>/api/chat</code> messages array, generate with the local
 * Ollama model, write back explicit "remember that ..." facts, and (optionally)
 * run an asynchronous <b>auto-learning</b> pass that extracts durable facts from
 * the exchange and stores them too.
 * <br><br>
 * Both front-ends drive this class &mdash; the standalone CLI ({@link Cli}) and
 * the web JSON-RPC service (<code>services.ChatService</code>) &mdash; so behavior
 * never drifts between them.  The engine owns Ollama-client creation so that the
 * reply and the background extraction each get their own (thread-safe) client.
 */
public final class ChatEngine {

    private static final Logger logger = LogManager.getLogger(ChatEngine.class);

    /** How many memories to recall per turn. */
    public static final int RECALL_LIMIT = 8;

    /** Default cap on how many recent history messages are sent to the model. */
    public static final int DEFAULT_HISTORY_MAX_MESSAGES = 20;

    /** Secondary cap: total characters of history sent (≈ 4 chars/token). */
    private static final int HISTORY_CHAR_BUDGET = 12000;

    /** Max tool-call rounds in the agentic loop before forcing a final answer. */
    private static final int AGENT_MAX_ROUNDS = 6;

    /** OwnSona tools the agentic model is allowed to call. */
    private static final Set<String> AGENT_TOOLS = new HashSet<>(Arrays.asList(
            "recall", "remember", "update_memory", "forget", "list_memories", "text_search"));

    /** Converted Ollama tool descriptors, cached after the first agentic call. */
    private static volatile JSONArray cachedAgentTools;

    /**
     * Single background thread for the auto-learning extraction pass.  Daemon, so
     * it never blocks JVM exit; single-threaded, so write-backs stay serialized
     * and never pile up if the user chats quickly.
     */
    private static final ExecutorService LEARN_POOL = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "llmchat-autolearn");
        t.setDaemon(true);
        return t;
    });

    private final OwnSonaClient os;
    private final String ollamaUrl;        // may be null/empty → Ollama default
    private volatile String model;
    private volatile boolean autoLearn = true;
    private volatile boolean agentic = false;
    private volatile int historyMaxMessages = DEFAULT_HISTORY_MAX_MESSAGES;

    /**
     * @param os        the OwnSona client (memory recall / write-back)
     * @param ollamaUrl base URL of the Ollama server (null/empty → localhost default)
     * @param model     the model name to generate with
     */
    public ChatEngine(OwnSonaClient os, String ollamaUrl, String model) {
        this.os = os;
        this.ollamaUrl = ollamaUrl;
        this.model = model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getModel() {
        return model;
    }

    /** Enable/disable the asynchronous auto-learning extraction pass. */
    public void setAutoLearn(boolean on) {
        this.autoLearn = on;
    }

    public boolean isAutoLearn() {
        return autoLearn;
    }

    /** Cap on how many recent history messages are sent to the model (&le; 0 ignored). */
    public void setHistoryMaxMessages(int n) {
        if (n > 0)
            this.historyMaxMessages = n;
    }

    public int getHistoryMaxMessages() {
        return historyMaxMessages;
    }

    /**
     * Enable the agentic tool-calling loop: instead of the fixed pipeline, the
     * model is given OwnSona's memory tools and decides itself when to recall /
     * remember / update / forget.  Requires a tool-calling-capable Ollama model;
     * default off (the fixed pipeline is the reliable default).
     */
    public void setAgentic(boolean on) {
        this.agentic = on;
    }

    public boolean isAgentic() {
        return agentic;
    }

    /** Whether the local Ollama server is reachable. */
    public boolean ollamaUp() {
        return newOllama().isOllamaUp();
    }

    /** List installed Ollama models. */
    public List<String> availableModels() throws IOException {
        return newOllama().getAvailableModels();
    }

    /**
     * Wait for any in-flight / queued auto-learning to finish, then stop the
     * background pool.  Intended for the CLI to call at exit so a fact stated in
     * the final turn still gets saved (the long-running web server never needs
     * this).  After this call, no further auto-learning runs in this JVM.
     *
     * @param seconds maximum time to wait
     */
    public static void drainAutoLearn(long seconds) {
        LEARN_POOL.shutdown();
        try {
            LEARN_POOL.awaitTermination(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Build a fresh Ollama client (each call is its own, so concurrent use is safe). */
    private Ollama newOllama() {
        final Ollama o = (ollamaUrl != null && !ollamaUrl.isEmpty())
                ? new Ollama(ollamaUrl, model)
                : new Ollama();
        o.selectModel(model);
        return o;
    }

    /** The result of one chat turn. */
    public static final class Turn {
        /** The assistant's reply (never null). */
        public final String answer;
        /** The memories that grounded the reply (never null; possibly empty). */
        public final List<Memory> usedMemories;
        /** The fact stored from an explicit "remember that ..." request, or null. */
        public final String remembered;
        /** Error message if an explicit write-back was attempted but failed, or null. */
        public final String rememberError;

        Turn(String answer, List<Memory> usedMemories, String remembered, String rememberError) {
            this.answer = answer;
            this.usedMemories = usedMemories;
            this.remembered = remembered;
            this.rememberError = rememberError;
        }
    }

    /**
     * Run one turn end-to-end.  The reply is produced synchronously; the
     * auto-learning extraction (if enabled) is dispatched to a background thread
     * and does not delay the returned {@link Turn}.
     *
     * @throws org.kissweb.oauth.client.OAuthAuthorizationRequiredException if OwnSona is not authorized
     * @throws Exception on recall or generation failure
     */
    public Turn respond(String message, List<Map<String, String>> history) throws Exception {
        if (agentic)
            return respondAgentic(message, history);

        final List<Memory> facts = os.recall(message, RECALL_LIMIT);
        final List<Map<String, String>> messages = buildMessages(facts, trimHistory(history), message);
        final String answer = newOllama().chat(messages);
        return finishTurn(message, answer, facts);
    }

    /**
     * Like {@link #respond} but streams the reply token-by-token to
     * <code>onToken</code> as the model generates it (fixed pipeline only — the
     * agentic loop is not streamed).  The returned {@link Turn} carries the full
     * accumulated answer.  Intended for the CLI; the web uses {@link #respond}.
     */
    public Turn respondStreaming(String message, List<Map<String, String>> history,
                                 Consumer<String> onToken) throws Exception {
        final List<Memory> facts = os.recall(message, RECALL_LIMIT);
        final List<Map<String, String>> messages = buildMessages(facts, trimHistory(history), message);
        final String answer = streamChat(messages, onToken);
        return finishTurn(message, answer, facts);
    }

    /** Explicit write-back + background auto-learning, shared by both respond paths. */
    private Turn finishTurn(String message, String answer, List<Memory> facts) {
        final String safe = (answer == null) ? "" : answer;

        // Explicit write-back: "remember that ..." (synchronous; fast; never fails the turn).
        String remembered = null;
        String rememberError = null;
        final String fact = extractExplicitRemember(message);
        if (fact != null) {
            try {
                os.remember(fact, null, "explicit", "skip_if_near");
                remembered = fact;
            } catch (Exception e) {
                rememberError = e.getMessage();
            }
        }

        // Auto-learning: extract durable facts in the background (never blocks the reply).
        if (autoLearn)
            LEARN_POOL.submit(() -> runAutoLearn(message, safe));

        return new Turn(safe, facts, remembered, rememberError);
    }

    /**
     * POST one /api/chat turn with <code>stream:true</code> and feed each content
     * chunk to <code>onToken</code> as it arrives; returns the full reply.
     */
    private String streamChat(List<Map<String, String>> messages, Consumer<String> onToken) throws IOException {
        String base = (ollamaUrl != null && !ollamaUrl.isEmpty()) ? ollamaUrl : "http://localhost:11434";
        if (!base.endsWith("/"))
            base = base + "/";
        final JSONArray msgArr = new JSONArray();
        for (Map<String, String> m : messages)
            msgArr.put(jsonMsg(m.get("role"), m.get("content")));
        final JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", msgArr);
        body.put("stream", true);

        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        final HttpResponse<Stream<String>> resp =
                new RestClient().streamCall(req, HttpResponse.BodyHandlers.ofLines());

        final StringBuilder full = new StringBuilder();
        final Iterator<String> it = resp.body().iterator();
        while (it.hasNext()) {
            final String line = it.next();
            if (line.isEmpty())
                continue;
            final JSONObject o;
            try {
                o = new JSONObject(line);
            } catch (RuntimeException e) {
                continue;                       // skip any non-JSON keepalive line
            }
            final JSONObject m = o.getJSONObject("message", false);
            if (m != null) {
                final String c = m.getString("content", "");
                if (!c.isEmpty()) {
                    full.append(c);
                    onToken.accept(c);
                }
            }
            if (o.getBoolean("done", false))
                break;
        }
        return full.toString();
    }

    // ====================================================================================
    // Auto-learning
    // ====================================================================================

    /**
     * Ask the model to extract durable facts about the user from the exchange and
     * store any it finds.  Best-effort: a failure here (model down, bad JSON,
     * OwnSona error) is logged and swallowed &mdash; it must never affect the chat.
     */
    private void runAutoLearn(String userMsg, String assistantMsg) {
        try {
            final String raw = newOllama().chat(extractionMessages(userMsg, assistantMsg));
            final List<NewFact> facts = parseFacts(raw);
            if (!facts.isEmpty())
                os.rememberBatch(facts, "skip_if_near");
        } catch (Exception e) {
            logger.info("auto-learn skipped: " + e.getMessage());
        }
    }

    private static List<Map<String, String>> extractionMessages(String userMsg, String assistantMsg) {
        final String sys =
                "You extract durable, long-term facts about the USER from a conversation, to save\n"
              + "to their personal memory. Return ONLY a JSON array of strings; each string is one\n"
              + "self-contained fact about the user — their preferences, relationships, projects,\n"
              + "identity, or decisions. Phrase each fact in the third person about the user (e.g.,\n"
              + "\"The user's favorite programming language is Forth\"), not first person. Include\n"
              + "only facts the user stated or clearly implied about themselves. EXCLUDE: questions,\n"
              + "ephemeral task chatter, facts about the assistant, general knowledge, and anything\n"
              + "not durably about the user. If there is nothing worth saving, return []. Output the\n"
              + "JSON array and nothing else.";
        final String user =
                "User said:\n" + userMsg + "\n\nAssistant replied:\n" + assistantMsg
              + "\n\nJSON array of durable facts about the user:";
        final List<Map<String, String>> m = new ArrayList<>();
        m.add(msg("system", sys));
        m.add(msg("user", user));
        return m;
    }

    /**
     * Parse the model's extraction output into facts.  Tolerant of surrounding
     * prose / code fences: it takes the outermost <code>[ ... ]</code> and accepts
     * only plain-string elements.
     */
    static List<NewFact> parseFacts(String raw) {
        final List<NewFact> out = new ArrayList<>();
        if (raw == null)
            return out;
        final int lb = raw.indexOf('[');
        final int rb = raw.lastIndexOf(']');
        if (lb < 0 || rb < lb)
            return out;
        final JSONArray arr;
        try {
            arr = new JSONArray(raw.substring(lb, rb + 1));
        } catch (RuntimeException e) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            final Object v = arr.opt(i);
            if (!(v instanceof String))
                continue;                       // only plain-string facts
            final String text = ((String) v).trim();
            if (!text.isEmpty())
                out.add(new NewFact(text));     // capture_mode defaults to "inferred"
        }
        return out;
    }

    // ====================================================================================
    // Agentic tool-calling loop (Phase 5)
    // ====================================================================================

    private static final String AGENT_SYSTEM_PROMPT =
            "You are a helpful assistant with access to the user's personal memory through tools.\n"
          + "Before answering questions about the user, call `recall` to retrieve relevant facts.\n"
          + "When the user states durable facts about themselves (preferences, relationships,\n"
          + "projects, decisions), call `remember` to save them, phrased in the third person\n"
          + "(e.g. \"The user prefers ...\"). If the user corrects a fact, use `update_memory`; if\n"
          + "they ask to forget something, use `forget`. Do not fabricate facts about the user.\n"
          + "After using tools, give a concise, direct answer.";

    /**
     * The agentic loop: advertise OwnSona's memory tools to the model and let it
     * drive recall/remember/update/forget itself, feeding tool results back until
     * it produces a final answer (bounded by {@link #AGENT_MAX_ROUNDS}).
     * <br><br>
     * Drives <code>/api/chat</code> directly via {@link RestClient} (not
     * {@link Ollama#chat}) because the loop must round-trip the assistant's
     * <code>tool_calls</code> and <code>role:"tool"</code> results, which the
     * string-only message form cannot represent.  Memory writes are performed by
     * the model, so the explicit/auto write-back paths are skipped here.
     */
    private Turn respondAgentic(String message, List<Map<String, String>> history) throws Exception {
        final JSONArray tools = agentTools();

        final JSONArray messages = new JSONArray();
        messages.put(jsonMsg("system", AGENT_SYSTEM_PROMPT));
        final List<Map<String, String>> trimmed = trimHistory(history);
        if (trimmed != null)
            for (Map<String, String> m : trimmed)
                messages.put(jsonMsg(m.get("role"), m.get("content")));
        messages.put(jsonMsg("user", message));

        final List<Memory> used = new ArrayList<>();
        String answer = "";

        for (int round = 0; round < AGENT_MAX_ROUNDS; round++) {
            final JSONObject reply = ollamaChatRaw(messages, tools);
            final String content = reply.getString("content", "");
            final JSONArray toolCalls = reply.has("tool_calls")
                    ? reply.getJSONArray("tool_calls", true) : null;

            if (toolCalls == null || toolCalls.length() == 0) {
                answer = content;
                break;
            }

            messages.put(reply);   // echo the assistant turn (with its tool_calls) back

            for (int i = 0; i < toolCalls.length(); i++) {
                final JSONObject fn = toolCalls.getJSONObject(i).getJSONObject("function", true);
                final String name = fn.getString("name", "");
                final JSONObject args = toArgs(fn.opt("arguments"));
                String resultText;
                try {
                    resultText = MCPClientBase.textOf(os.callTool(name, args));
                    if ("recall".equals(name))
                        collectRecall(resultText, used);
                } catch (Exception e) {
                    resultText = new JSONObject().put("ok", false)
                            .put("error", String.valueOf(e.getMessage())).toString();
                }
                final JSONObject toolMsg = new JSONObject();
                toolMsg.put("role", "tool");
                toolMsg.put("name", name);
                toolMsg.put("content", resultText);
                messages.put(toolMsg);
            }

            if (round == AGENT_MAX_ROUNDS - 1)
                answer = (content == null || content.isEmpty())
                        ? "(stopped after " + AGENT_MAX_ROUNDS + " tool rounds)" : content;
        }

        return new Turn(answer == null ? "" : answer, used, null, null);
    }

    /** Build (and cache) the Ollama tool descriptors from OwnSona's tool catalog. */
    private JSONArray agentTools() {
        JSONArray cached = cachedAgentTools;
        if (cached != null)
            return cached;
        final JSONArray out = new JSONArray();
        final JSONArray mcp = os.listTools();
        for (int i = 0; i < mcp.length(); i++) {
            final JSONObject t = mcp.getJSONObject(i);
            final String name = t.getString("name", "");
            if (!AGENT_TOOLS.contains(name))
                continue;
            final JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("description", t.getString("description", ""));
            final JSONObject params = t.getJSONObject("inputSchema", false);
            fn.put("parameters", params != null ? params : new JSONObject());
            final JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", fn);
            out.put(tool);
        }
        cachedAgentTools = out;
        return out;
    }

    /** POST one /api/chat turn (with tools) and return the assistant `message` object. */
    private JSONObject ollamaChatRaw(JSONArray messages, JSONArray tools) throws IOException {
        String base = (ollamaUrl != null && !ollamaUrl.isEmpty()) ? ollamaUrl : "http://localhost:11434";
        if (!base.endsWith("/"))
            base = base + "/";
        final JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        if (tools != null && tools.length() > 0)
            body.put("tools", tools);
        body.put("stream", false);
        final String str = new RestClient().strCall("POST", base + "api/chat", body.toString());
        if (str == null || str.isEmpty())
            return new JSONObject();
        return new JSONObject(str).getJSONObject("message", true);
    }

    private static JSONObject jsonMsg(String role, String content) {
        final JSONObject o = new JSONObject();
        o.put("role", (role == null || role.isEmpty()) ? "user" : role);
        o.put("content", content == null ? "" : content);
        return o;
    }

    private static JSONObject toArgs(Object arguments) {
        if (arguments instanceof JSONObject)
            return (JSONObject) arguments;
        if (arguments instanceof String) {
            try {
                return new JSONObject((String) arguments);
            } catch (RuntimeException e) {
                return new JSONObject();
            }
        }
        return new JSONObject();
    }

    private static void collectRecall(String resultText, List<Memory> used) {
        try {
            final JSONArray matches = new JSONObject(resultText).getJSONArray("matches", true);
            for (int i = 0; i < matches.length(); i++)
                used.add(Memory.fromJson(matches.getJSONObject(i)));
        } catch (RuntimeException ignore) {
            // result wasn't recall-shaped; nothing to collect
        }
    }

    // ====================================================================================
    // Prompt building
    // ====================================================================================

    /**
     * Assemble the <code>/api/chat</code> messages array: a system message
     * carrying the recalled facts, the prior history, then the user message.
     */
    public static List<Map<String, String>> buildMessages(List<Memory> facts,
                                                          List<Map<String, String>> history,
                                                          String message) {
        final List<Map<String, String>> messages = new ArrayList<>();

        final StringBuilder sys = new StringBuilder();
        sys.append("You are a helpful assistant with access to the user's personal memory.\n");
        sys.append("Use the FACTS below when relevant. If a fact conflicts with what the user\n");
        sys.append("says now, trust the user. Do not fabricate facts about the user.\n");
        if (facts != null && !facts.isEmpty()) {
            sys.append("\nKNOWN FACTS ABOUT THE USER (most relevant first):\n");
            for (Memory m : facts)
                sys.append("- ").append(m.text).append("\n");
        }
        messages.add(msg("system", sys.toString()));

        if (history != null)
            messages.addAll(history);

        messages.add(msg("user", message));
        return messages;
    }

    /**
     * Keep only the most recent history that fits the message-count and character
     * budgets (oldest dropped), so the prompt never grows without bound.  Also
     * avoids starting the kept history with an assistant turn.
     */
    private List<Map<String, String>> trimHistory(List<Map<String, String>> history) {
        if (history == null || history.isEmpty())
            return history;
        final List<Map<String, String>> kept = new ArrayList<>();
        int chars = 0;
        for (int i = history.size() - 1; i >= 0 && kept.size() < historyMaxMessages; i--) {
            final Map<String, String> m = history.get(i);
            final String c = m.get("content");
            final int len = (c == null) ? 0 : c.length();
            if (!kept.isEmpty() && chars + len > HISTORY_CHAR_BUDGET)
                break;
            chars += len;
            kept.add(m);
        }
        Collections.reverse(kept);
        if (!kept.isEmpty() && "assistant".equals(kept.get(0).get("role")))
            kept.remove(0);
        return kept;
    }

    /** Build a single {@code {role, content}} message map. */
    public static Map<String, String> msg(String role, String content) {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * If the message begins with a "remember that ..." style phrase, return the
     * remainder to store; otherwise null.
     */
    public static String extractExplicitRemember(String message) {
        if (message == null)
            return null;
        final String m = message.trim();
        final String lower = m.toLowerCase();
        final String[] prefixes = {
                "please remember that ",
                "remember that ",
                "remember this: ",
                "remember: ",
                "remember "
        };
        for (String p : prefixes) {
            if (lower.startsWith(p)) {
                final String rest = m.substring(p.length()).trim();
                if (!rest.isEmpty())
                    return rest;
            }
        }
        return null;
    }
}
