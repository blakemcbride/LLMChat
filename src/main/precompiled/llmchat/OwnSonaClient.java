package llmchat;

import org.kissweb.MCPClientBase;
import org.kissweb.json.JSONArray;
import org.kissweb.json.JSONObject;
import org.kissweb.restServer.MainServlet;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed client for the cloud <b>OwnSona</b> personal-memory MCP server.
 * <br><br>
 * All of the MCP wire protocol, the OAuth 2.1 bearer-token handling (with
 * transparent refresh), and the unwrapping of tool-result content blocks are
 * provided by {@link MCPClientBase}.  This class adds only the OwnSona-specific
 * pieces: which endpoint to talk to, which OAuth provider to authenticate with,
 * and typed wrappers around the handful of tools LLMChat uses.
 * <br><br>
 * Configuration comes from <code>application.ini</code>:
 * <ul>
 *   <li><code>OwnSonaMcpUrl</code> in <code>[main]</code> --- the <code>/mcp</code> endpoint</li>
 *   <li><code>[OAuthClient ownsona]</code> section --- the OAuth client settings</li>
 * </ul>
 * Authentication is automatic: because {@link #getOAuthProviderName()} returns
 * <code>"ownsona"</code>, every call carries a bearer token.  When no usable
 * token is held, an
 * {@link org.kissweb.oauth.client.OAuthAuthorizationRequiredException} propagates
 * out of the call and the caller should start the browser login flow (see
 * {@link Cli}'s interactive-login handling).
 */
public class OwnSonaClient extends MCPClientBase {

    @Override
    protected String getServerUrl() {
        final Object url = MainServlet.getEnvironment("OwnSonaMcpUrl");
        return url == null ? null : url.toString();
    }

    @Override
    protected String getOAuthProviderName() {
        return "ownsona";
    }

    @Override
    protected String getClientName() {
        return "llmchat";
    }

    // ====================================================================================
    // Reads
    // ====================================================================================

    /**
     * Recall the most relevant memories for a query via vector similarity.
     *
     * @param query the natural-language query
     * @param limit the maximum number of memories to return
     * @return the matching memories, highest-scoring first (possibly empty)
     */
    public List<Memory> recall(String query, int limit) {
        final JSONObject args = new JSONObject();
        args.put("query", query);
        args.put("limit", limit);
        final JSONObject result = parse(callToolText("recall", args));
        final List<Memory> out = new ArrayList<>();
        final JSONArray matches = result.getJSONArray("matches", true);
        for (int i = 0; i < matches.length(); i++)
            out.add(Memory.fromJson(matches.getJSONObject(i)));
        return out;
    }

    // ====================================================================================
    // Writes
    // ====================================================================================

    /**
     * Store a single memory.
     *
     * @param text the fact to remember
     * @param tags tags to attach (may be null/empty)
     * @param captureMode "explicit" (user-directed) or "inferred" (auto-extracted)
     * @param dedupPolicy OwnSona dedup policy, e.g. "skip_if_near" (may be null)
     * @return the new memory id (0 if OwnSona did not return one)
     */
    public long remember(String text, List<String> tags, String captureMode, String dedupPolicy) {
        final JSONObject args = new JSONObject();
        args.put("text", text);
        if (tags != null && !tags.isEmpty())
            args.put("tags", tags);
        if (captureMode != null && !captureMode.isEmpty())
            args.put("capture_mode", captureMode);
        if (dedupPolicy != null && !dedupPolicy.isEmpty())
            args.put("dedup_policy", dedupPolicy);
        final JSONObject result = parse(callToolText("remember", args));
        return result.getLong("memory_id", 0L);
    }

    /**
     * Store many memories in one call.
     *
     * @param facts the facts to store (null/empty is a no-op)
     * @param dedupPolicy default dedup policy for the batch, e.g. "skip_if_near" (may be null)
     */
    public void rememberBatch(List<NewFact> facts, String dedupPolicy) {
        if (facts == null || facts.isEmpty())
            return;
        final JSONArray items = new JSONArray();
        for (NewFact f : facts)
            items.put(f.toArgs());
        final JSONObject args = new JSONObject();
        args.put("items", items);
        if (dedupPolicy != null && !dedupPolicy.isEmpty())
            args.put("dedup_policy", dedupPolicy);
        callToolText("remember_batch", args);
    }

    /**
     * Correct an existing memory's text.
     *
     * @param id the memory id
     * @param text the new text
     */
    public void updateMemory(long id, String text) {
        final JSONObject args = new JSONObject();
        args.put("id", id);
        args.put("text", text);
        callToolText("update_memory", args);
    }

    /**
     * Soft-delete a memory.
     *
     * @param id the memory id
     */
    public void forget(long id) {
        final JSONObject args = new JSONObject();
        args.put("id", id);
        callToolText("forget", args);
    }

    // ====================================================================================
    // Internals
    // ====================================================================================

    /**
     * Parse an OwnSona tool result.  OwnSona returns its result as a JSON
     * document inside the MCP text content block, which {@link #callToolText}
     * already extracts; here we turn that string into a {@link JSONObject}.
     */
    private static JSONObject parse(String text) {
        if (text == null || text.isEmpty())
            return new JSONObject();
        return new JSONObject(text);
    }
}
