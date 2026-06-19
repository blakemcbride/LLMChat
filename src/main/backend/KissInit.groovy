import org.kissweb.database.Connection
import org.kissweb.restServer.MainServlet
import org.kissweb.restServer.UserCache
import org.kissweb.restServer.UserData
import java.util.function.Consumer

class KissInit {

    /**
     * Configure the system.
     */
    static void init() {

        MainServlet.readIniFile "application.ini", "main"

        // LLMChat web UI has no login — allow its services without authentication.
        // (Localhost personal tool; OwnSona's OAuth is the real gate.)
        MainServlet.allowWithoutAuthentication("services.ChatService", "send")
        MainServlet.allowWithoutAuthentication("services.OwnSonaAuth", "status")
        MainServlet.allowWithoutAuthentication("services.OwnSonaAuth", "beginLogin")
        MainServlet.allowWithoutAuthentication("services.OwnSonaAuth", "logout")
        MainServlet.allowWithoutAuthentication("services.OllamaInfo", "health")
        MainServlet.allowWithoutAuthentication("services.OllamaInfo", "models")

        // Set up a global logout handler that runs whenever any user logs out
        // This can be used for cleanup tasks like logging, closing resources, etc.
        UserCache.setLogoutHandler({ UserData ud ->
            // Example: Log the logout event
            println "User ${ud.getUsername()} (ID: ${ud.getUserId()}) is logging out"

            // Add any custom cleanup code here
            // Examples:
            // - Close user-specific resources
            // - Update database logout timestamp
            // - Send notifications
            // - Clean up temporary files
        } as Consumer<UserData>)

    }

    /**
     * Code to run once the database is open but before the app is running.
     */
    static void init2(Connection db) {
        // If you use db, make sure you commit.
    }
}
