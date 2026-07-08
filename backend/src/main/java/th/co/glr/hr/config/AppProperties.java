package th.co.glr.hr.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Cors cors = new Cors();
    private final Attendance attendance = new Attendance();
    private final LoginRateLimit loginRateLimit = new LoginRateLimit();
    private final Auth auth = new Auth();
    private final Leave leave = new Leave();

    public Cors getCors() {
        return cors;
    }

    public Attendance getAttendance() {
        return attendance;
    }

    public LoginRateLimit getLoginRateLimit() {
        return loginRateLimit;
    }

    public Auth getAuth() {
        return auth;
    }

    public Leave getLeave() {
        return leave;
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

    public static class Attendance {
        private String agentToken;

        public String getAgentToken() {
            return agentToken;
        }

        public void setAgentToken(String agentToken) {
            this.agentToken = agentToken;
        }
    }

    public static class Leave {
        private int advanceNoticeDays = 7;

        public int getAdvanceNoticeDays() {
            return advanceNoticeDays;
        }

        public void setAdvanceNoticeDays(int advanceNoticeDays) {
            this.advanceNoticeDays = advanceNoticeDays;
        }
    }

    /** Authentication/onboarding toggles. */
    public static class Auth {
        /**
         * When true, {@code PasswordBackfillRunner} seeds each password-less active employee's
         * {@code employee_code} as a temporary login password on startup. OFF by default because
         * the low-entropy code is guessable before first login; opt in for local dev only.
         */
        private boolean seedEmployeeCodePasswords = false;

        public boolean isSeedEmployeeCodePasswords() {
            return seedEmployeeCodePasswords;
        }

        public void setSeedEmployeeCodePasswords(boolean seedEmployeeCodePasswords) {
            this.seedEmployeeCodePasswords = seedEmployeeCodePasswords;
        }
    }

    /** Brute-force protection for POST /api/auth/login (advisory GHSA-8m9r-9vhj-mr52). */
    public static class LoginRateLimit {
        private boolean enabled = true;
        /** Failed attempts allowed per account (email) within the window before lockout. */
        private int maxAccountFailures = 5;
        /** Failed attempts allowed per client IP within the window before lockout. */
        private int maxIpFailures = 20;
        /** Sliding window over which failures are counted, in seconds. */
        private long windowSeconds = 900;
        /** How long a key stays locked once it trips the limit, in seconds. */
        private long lockoutSeconds = 900;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAccountFailures() {
            return maxAccountFailures;
        }

        public void setMaxAccountFailures(int maxAccountFailures) {
            this.maxAccountFailures = maxAccountFailures;
        }

        public int getMaxIpFailures() {
            return maxIpFailures;
        }

        public void setMaxIpFailures(int maxIpFailures) {
            this.maxIpFailures = maxIpFailures;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public long getLockoutSeconds() {
            return lockoutSeconds;
        }

        public void setLockoutSeconds(long lockoutSeconds) {
            this.lockoutSeconds = lockoutSeconds;
        }
    }

}
