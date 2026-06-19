package llmchat;

import org.kissweb.json.JSONArray;
import org.kissweb.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A single memory recalled from the OwnSona personal-memory MCP server.
 * <br><br>
 * This is a plain data holder populated from the JSON OwnSona returns for a
 * <code>recall</code> match.  Only the fields LLMChat actually uses are mapped;
 * OwnSona returns more (importance, salience, etc.) which are ignored here.
 */
public class Memory {

    /** OwnSona's unique memory id. */
    public long id;

    /** The remembered fact text. */
    public String text;

    /** Cosine-similarity score for this match (0..1); higher is more relevant. */
    public double score;

    /** ISO-8601 creation timestamp as returned by OwnSona (may be null). */
    public String createdAt;

    /** Tags attached to the memory (never null; empty when none). */
    public List<String> tags = new ArrayList<>();

    /**
     * Build a Memory from one element of OwnSona's <code>recall</code>
     * <code>matches</code> array.
     *
     * @param o a match object
     * @return the populated Memory
     */
    public static Memory fromJson(JSONObject o) {
        final Memory m = new Memory();
        m.id        = o.getLong("id", 0L);
        m.text      = o.getString("text", "");
        m.score     = o.getDouble("score", 0.0);
        m.createdAt = o.getString("created_at", null);
        final JSONArray t = o.getJSONArray("tags", true);
        for (int i = 0; i < t.length(); i++)
            m.tags.add(t.getString(i));
        return m;
    }

    /**
     * Render this Memory as a JSON object suitable for returning to the
     * frontend (so the chat UI can show "grounded in N memories").
     *
     * @return a JSON representation
     */
    public JSONObject toJson() {
        final JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("text", text);
        o.put("score", score);
        o.put("createdAt", createdAt);
        o.put("tags", tags);
        return o;
    }
}
