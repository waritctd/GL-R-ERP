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
    private final Overtime overtime = new Overtime();
    private final SpecialMoney specialMoney = new SpecialMoney();
    private final Bot bot = new Bot();
    private final FactoryQuoteDispatch factoryQuoteDispatch = new FactoryQuoteDispatch();

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

    public Overtime getOvertime() {
        return overtime;
    }

    public SpecialMoney getSpecialMoney() {
        return specialMoney;
    }

    public Bot getBot() {
        return bot;
    }

    public FactoryQuoteDispatch getFactoryQuoteDispatch() {
        return factoryQuoteDispatch;
    }

    public static class Bot {
        private String apiToken = "";

        public String getApiToken() {
            return apiToken;
        }

        public void setApiToken(String apiToken) {
            this.apiToken = apiToken;
        }
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
        private final Schedule schedule = new Schedule();
        private final Daily daily = new Daily();

        public String getAgentToken() {
            return agentToken;
        }

        public void setAgentToken(String agentToken) {
            this.agentToken = agentToken;
        }

        public Schedule getSchedule() {
            return schedule;
        }

        public Daily getDaily() {
            return daily;
        }
    }

    /**
     * The company-wide standard working day used to derive late / early-leave minutes.
     *
     * <p>Company-wide is deliberate: there is no per-employee or per-division schedule yet. Read
     * this through {@code WorkScheduleResolver} rather than injecting it into calculation code, so
     * adding per-division schedules later is a new resolver implementation instead of a rewrite.
     */
    public static class Schedule {
        private String zone = "Asia/Bangkok";
        private String workStart = "08:30";
        private String workEnd = "17:30";
        /**
         * Minutes after {@code workStart} before a check-in counts as late. This is a
         * <strong>threshold, not an allowance</strong>: with a 5-minute grace, arriving 08:34 is 0
         * late minutes, but arriving 08:40 is <em>10</em> late minutes measured from 08:30 — not 4.
         */
        private int graceMinutes = 5;
        private List<String> workdays = new ArrayList<>(
            List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")
        );

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public String getWorkStart() {
            return workStart;
        }

        public void setWorkStart(String workStart) {
            this.workStart = workStart;
        }

        public String getWorkEnd() {
            return workEnd;
        }

        public void setWorkEnd(String workEnd) {
            this.workEnd = workEnd;
        }

        public int getGraceMinutes() {
            return graceMinutes;
        }

        public void setGraceMinutes(int graceMinutes) {
            this.graceMinutes = graceMinutes;
        }

        public List<String> getWorkdays() {
            return workdays;
        }

        public void setWorkdays(List<String> workdays) {
            this.workdays = workdays;
        }
    }

    /** Roll-up of raw punches into hr.attendance_daily. */
    public static class Daily {
        private boolean recalcEnabled = true;
        /** How many trailing days the nightly job re-derives, to heal late catch-up pulls. */
        private int recalcLookbackDays = 7;
        /** One-shot historical roll-up on startup. Off by default; drive it from the HR endpoint. */
        private boolean backfillOnStartup = false;

        public boolean isRecalcEnabled() {
            return recalcEnabled;
        }

        public void setRecalcEnabled(boolean recalcEnabled) {
            this.recalcEnabled = recalcEnabled;
        }

        public int getRecalcLookbackDays() {
            return recalcLookbackDays;
        }

        public void setRecalcLookbackDays(int recalcLookbackDays) {
            this.recalcLookbackDays = recalcLookbackDays;
        }

        public boolean isBackfillOnStartup() {
            return backfillOnStartup;
        }

        public void setBackfillOnStartup(boolean backfillOnStartup) {
            this.backfillOnStartup = backfillOnStartup;
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

    public static class Overtime {
        /**
         * How far back a request may reach. The advance-notice rule it replaced was removed on CEO
         * instruction: overtime may now be filed same-day or retroactively, provided the reason
         * explains why. This bound only stops claims arriving so late they can no longer be paid.
         */
        private int retroactiveWindowDays = 60;

        public int getRetroactiveWindowDays() {
            return retroactiveWindowDays;
        }

        public void setRetroactiveWindowDays(int retroactiveWindowDays) {
            this.retroactiveWindowDays = retroactiveWindowDays;
        }
    }

    /**
     * Tuning for {@code FactoryQuoteEmailDispatchWorker}, the background outbox worker that sends
     * factory quote request emails asynchronously (see V67).
     */
    public static class FactoryQuoteDispatch {
        /** How often the worker polls for claimable dispatch rows. */
        private long pollIntervalMs = 5000;
        /**
         * How long a claim on a SENDING row is honoured before another worker tick may reclaim it
         * as stale (the fix for a worker crashing between claim and finalize).
         */
        private int reclaimTimeoutSeconds = 120;
        /** Attempts beyond this are never reclaimed again; the row is left FAILED permanently. */
        private int maxAttempts = 8;
        /** Backoff unit: next_attempt_at = now() + attemptCount * this, after a failed attempt. */
        private int backoffBaseSeconds = 30;
        /** Rows claimed per worker tick. */
        private int batchSize = 20;

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getReclaimTimeoutSeconds() {
            return reclaimTimeoutSeconds;
        }

        public void setReclaimTimeoutSeconds(int reclaimTimeoutSeconds) {
            this.reclaimTimeoutSeconds = reclaimTimeoutSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getBackoffBaseSeconds() {
            return backoffBaseSeconds;
        }

        public void setBackoffBaseSeconds(int backoffBaseSeconds) {
            this.backoffBaseSeconds = backoffBaseSeconds;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
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

    public static class SpecialMoney {
        /**
         * The 25th-of-month payroll cutoff: a special-money request approved on/before this day of
         * the month lands in that same month's payroll_month; approved after, it rolls to next
         * month. See {@code SpecialMoneyService#ceoApprove} for why it then rolls further forward
         * past any already-PROCESSED month.
         */
        private int payrollCutoffDay = 25;

        public int getPayrollCutoffDay() {
            return payrollCutoffDay;
        }

        public void setPayrollCutoffDay(int payrollCutoffDay) {
            this.payrollCutoffDay = payrollCutoffDay;
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
