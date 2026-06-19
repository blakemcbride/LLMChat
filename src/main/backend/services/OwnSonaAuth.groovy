package services

import org.kissweb.json.JSONObject
import org.kissweb.database.Connection
import org.kissweb.restServer.ProcessServlet
import org.kissweb.restServer.MainServlet
import org.kissweb.oauth.client.OAuthClient
import org.kissweb.oauth.client.OAuthClientConfig

/**
 * Manages the LLMChat &harr; OwnSona OAuth client connection for the web UI.
 *
 * The token exchange after the user logs in at ownsona.com is handled by the
 * framework's /oauth/client/callback servlet (web mode only) &mdash; no code here.
 */
class OwnSonaAuth {

    private static final String PROVIDER = "ownsona"

    /** Report whether a usable (or refreshable) OwnSona token is held. */
    void status(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        outjson.put("connected", OAuthClient.forProvider(PROVIDER).isAuthorized())
    }

    /** Begin the authorization-code + PKCE flow; returns the browser login URL. */
    void beginLogin(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        OAuthClientConfig.reset()   // pick up application.ini edits without a restart
        OAuthClient client = OAuthClient.forProvider(PROVIDER)
        client.clearTokens()        // drop any stale token so the flow re-issues a correct one
        String base = MainServlet.getEnvironment("OAuthClientRedirectBaseUrl")
        if (base == null || base.isEmpty())
            base = "http://localhost:8080"
        outjson.put("authUrl", client.beginAuthorization(base))
    }

    /** Discard the stored OwnSona tokens (does not revoke them server-side). */
    void logout(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        OAuthClient.forProvider(PROVIDER).clearTokens()
        outjson.put("connected", false)
    }
}
