package services

import org.kissweb.json.JSONObject
import org.kissweb.json.JSONArray
import org.kissweb.database.Connection
import org.kissweb.restServer.ProcessServlet
import org.kissweb.restServer.MainServlet
import org.kissweb.llm.Ollama
import org.kissweb.oauth.client.OAuthAuthorizationRequiredException
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

import llmchat.OwnSonaClient
import llmchat.ChatEngine
import llmchat.Memory

/**
 * Web (JSON-RPC) front-end for the chat loop.  All the actual work is done by
 * the shared {@link llmchat.ChatEngine}, which is the same class the standalone
 * CLI uses, so the two interfaces behave identically.
 */
class ChatService {

    private static final Logger logger = LogManager.getLogger(ChatService.class)

    /**
     * Input:  { message, model?, history?: [ {role, content}, ... ] }
     * Output: { answer, htmlAnswer, usedMemories[], remembered? }  or  { needsLogin } / { error }
     */
    void send(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String message = injson.getString("message")
        String model = injson.getString("model", null)
        JSONArray history = injson.getJSONArray("history", true)

        if (model == null || model.isEmpty())
            model = OllamaInfo.effectiveDefaultModel()
        String url = MainServlet.getEnvironment("OllamaUrl")

        OwnSonaClient os = new OwnSonaClient()
        ChatEngine engine = new ChatEngine(os, url, model)
        engine.setAutoLearn(autoLearnEnabled())
        engine.setHistoryMaxMessages(historyMaxMessages())
        engine.setAgentic(agenticEnabled())

        // Convert the JSON history into the engine's message format.
        List<Map<String, String>> hist = new ArrayList<>()
        if (history != null) {
            for (int i = 0; i < history.length(); i++) {
                JSONObject t = history.getJSONObject(i)
                String role = t.getString("role", "user")
                if (role != "user" && role != "assistant")
                    role = "user"
                hist.add(ChatEngine.msg(role, t.getString("content", "")))
            }
        }

        ChatEngine.Turn turn
        try {
            turn = engine.respond(message, hist)
        } catch (OAuthAuthorizationRequiredException e) {
            logger.info("OwnSona authorization required: " + e.getMessage())
            outjson.put("needsLogin", true)
            return
        } catch (Exception e) {
            logger.error("Chat turn failed", e)
            outjson.put("error", e.getMessage())
            return
        }

        outjson.put("answer", turn.answer)
        outjson.put("htmlAnswer", Ollama.toHtml(turn.answer))
        JSONArray used = new JSONArray()
        for (Memory m : turn.usedMemories)
            used.put(m.toJson())
        outjson.put("usedMemories", used)
        if (turn.remembered != null)
            outjson.put("remembered", turn.remembered)
    }

    /** Whether auto-learning is enabled (the AutoLearn config key; default on). */
    private static boolean autoLearnEnabled() {
        String v = MainServlet.getEnvironment("AutoLearn")
        if (v == null || v.isEmpty())
            return true
        v = v.trim()
        return !v.equalsIgnoreCase("false") && !v.equalsIgnoreCase("off") && v != "0"
    }

    /** History message cap sent to the model (HistoryMaxMessages config; default 20). */
    private static int historyMaxMessages() {
        String v = MainServlet.getEnvironment("HistoryMaxMessages")
        if (v == null || v.isEmpty())
            return ChatEngine.DEFAULT_HISTORY_MAX_MESSAGES
        try {
            return Integer.parseInt(v.trim())
        } catch (NumberFormatException e) {
            return ChatEngine.DEFAULT_HISTORY_MAX_MESSAGES
        }
    }

    /** Whether the agentic tool-calling loop is enabled (the Agentic config key; default off). */
    private static boolean agenticEnabled() {
        String v = MainServlet.getEnvironment("Agentic")
        if (v == null || v.isEmpty())
            return false
        v = v.trim()
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on") || v == "1"
    }
}
