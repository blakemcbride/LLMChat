package services

import org.kissweb.json.JSONObject
import org.kissweb.database.Connection
import org.kissweb.restServer.ProcessServlet
import org.kissweb.restServer.MainServlet
import org.kissweb.llm.Ollama

/**
 * Read-only information about the local Ollama server for the web UI: a health
 * check and the list of installed models (to populate the model dropdown).
 */
class OllamaInfo {

    private static Ollama newClient() {
        String url = MainServlet.getEnvironment("OllamaUrl")
        return (url != null && !url.isEmpty()) ? new Ollama(url) : new Ollama()
    }

    /** Whether the local Ollama server is reachable. */
    void health(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        outjson.put("up", newClient().isOllamaUp())
    }

    /** List installed Ollama models, plus the effective default model. */
    void models(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        outjson.put("models", newClient().getAvailableModels())
        outjson.put("defaultModel", effectiveDefaultModel())
    }

    /** The startup -m/--model override (LLMCHAT_MODEL) if set, else the application.ini default. */
    static String effectiveDefaultModel() {
        String m = System.getenv("LLMCHAT_MODEL")
        if (m == null || m.isEmpty())
            m = MainServlet.getEnvironment("OllamaModel")
        return m
    }
}
