package th.co.glr.hr.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Cors cors = new Cors();
    private final DemoSeed demoSeed = new DemoSeed();
    private final Auth auth = new Auth();

    public Cors getCors() {
        return cors;
    }

    public DemoSeed getDemoSeed() {
        return demoSeed;
    }

    public Auth getAuth() {
        return auth;
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(
            List.of("http://localhost:5173", "http://localhost:5174", "http://127.0.0.1:5173", "http://127.0.0.1:5174")
        );

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class DemoSeed {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Auth {
        private boolean quickRoleLoginEnabled;

        public boolean isQuickRoleLoginEnabled() {
            return quickRoleLoginEnabled;
        }

        public void setQuickRoleLoginEnabled(boolean quickRoleLoginEnabled) {
            this.quickRoleLoginEnabled = quickRoleLoginEnabled;
        }
    }
}
