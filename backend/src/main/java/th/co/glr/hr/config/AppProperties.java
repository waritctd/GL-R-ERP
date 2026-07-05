package th.co.glr.hr.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Cors cors = new Cors();
    private final Attendance attendance = new Attendance();
    private final LoginRateLimit loginRateLimit = new LoginRateLimit();

    public Cors getCors() {
        return cors;
    }

    public Attendance getAttendance() {
        return attendance;
    }

    public LoginRateLimit getLoginRateLimit() {
        return loginRateLimit;
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
        /** Standard shift start (Asia/Bangkok). Arrivals after start+grace accrue late minutes. */
        private String standardStartTime = "08:30";
        /** Standard shift end (Asia/Bangkok). Departures before this accrue early-leave minutes. */
        private String standardEndTime = "17:30";
        /** Grace window after start before lateness is counted. Informational only; never deducted. */
        private int lateGraceMinutes = 0;
        /**
         * Scheduled workdays. A workday with no punch and no approved leave is an unpaid absence
         * (no-work-no-pay). Public holidays are not modelled yet — handle via approved leave for now.
         */
        private List<String> workdays = new ArrayList<>(
            List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")
        );

        public String getAgentToken() {
            return agentToken;
        }

        public void setAgentToken(String agentToken) {
            this.agentToken = agentToken;
        }

        public String getStandardStartTime() {
            return standardStartTime;
        }

        public void setStandardStartTime(String standardStartTime) {
            this.standardStartTime = standardStartTime;
        }

        public String getStandardEndTime() {
            return standardEndTime;
        }

        public void setStandardEndTime(String standardEndTime) {
            this.standardEndTime = standardEndTime;
        }

        public int getLateGraceMinutes() {
            return lateGraceMinutes;
        }

        public void setLateGraceMinutes(int lateGraceMinutes) {
            this.lateGraceMinutes = lateGraceMinutes;
        }

        public List<String> getWorkdays() {
            return workdays;
        }

        public void setWorkdays(List<String> workdays) {
            this.workdays = workdays;
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
