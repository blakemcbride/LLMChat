package llmchat;

import org.kissweb.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A candidate fact to write back to OwnSona via <code>remember</code> /
 * <code>remember_batch</code>.
 * <br><br>
 * Used both for the explicit "remember that ..." path and for the LLM
 * extraction pass.  Defaults to <code>capture_mode = "inferred"</code>; set it
 * to <code>"explicit"</code> when the user literally asked to remember the fact.
 */
public class NewFact {

    /** The fact text to store. */
    public String text;

    /** Tags to attach (never null; empty when none). */
    public List<String> tags = new ArrayList<>();

    /** OwnSona capture mode: "explicit" (user-directed) or "inferred" (auto-extracted). */
    public String captureMode = "inferred";

    /** Create an empty fact. */
    public NewFact() {
    }

    /**
     * Create an inferred fact with the given text.
     *
     * @param text the fact text
     */
    public NewFact(String text) {
        this.text = text;
    }

    /**
     * Build the per-item arguments object OwnSona's <code>remember</code> /
     * <code>remember_batch</code> tool expects.
     *
     * @return the arguments object
     */
    public JSONObject toArgs() {
        final JSONObject o = new JSONObject();
        o.put("text", text);
        if (tags != null && !tags.isEmpty())
            o.put("tags", tags);
        if (captureMode != null && !captureMode.isEmpty())
            o.put("capture_mode", captureMode);
        return o;
    }
}
