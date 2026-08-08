import { request as playwrightRequest } from '@playwright/test';
import { BACKEND_URL } from './helpers/api.js';
import { DEMO_PASSWORD, PERSONAS } from './helpers/accounts.js';

// The origin the browser will actually send once Playwright starts the dev server. Must match
// playwright.real.config.js's FRONTEND_PORT.
const FRONTEND_ORIGIN = `http://127.0.0.1:${process.env.E2E_REAL_FRONTEND_PORT || 5251}`;

const HOW_TO_RUN = `
  Bring the stack up first (see frontend/e2e-real/README.md):

    # 1. Postgres on :5432 with a 'hris' database
    docker compose up -d db          # or a local cluster: pg_ctlcluster 16 main start

    # 2. Backend on :8080, with the demo seed profile and this suite's CORS origin
    cd backend && ./mvnw -DskipTests package
    cd ../frontend && node scripts/start-e2e-backend.mjs

    # 3. The suite, in another terminal
    cd frontend && npm run test:e2e
`;

/**
 * Preflight for the real-backend suite.
 *
 * Four setup mistakes otherwise surface as a wall of identically red specs. Each is detected
 * here and reported as one actionable message before a single test runs:
 *   1. no backend at all → every spec fails on a connection refused
 *   2. the suite's origin missing from app.cors.allowed-origins → every login gets a bare 403
 *   3. the login rate limiter is locked out → every login gets a 429
 *   4. backend running WITHOUT the demo profile → every login gets a 401, because
 *      db/migration-demo never ran and none of these accounts exist
 *
 * 2, 3 and 4 are the sneaky ones — in all three the backend is up and perfectly healthy, and
 * the only symptom is that logging in stops working for a reason the login form cannot
 * distinguish from a wrong password.
 *
 * Order matters. The CORS check runs first and uses an OPTIONS preflight rather than a real
 * login, because a CORS-rejected POST /api/auth/login is a 403 and LoginRateLimitFilter counts
 * 403s as auth failures — so probing CORS with a login would turn mistake 2 into mistake 3 and
 * lock the account out for app.login-rate-limit.lockout-seconds (900s by default). OPTIONS is
 * exempt (LoginRateLimitFilter#shouldNotFilter matches POST only), so this costs nothing.
 */
export default async function globalSetup() {
  const context = await playwrightRequest.newContext({ baseURL: BACKEND_URL });

  try {
    let health;
    try {
      health = await context.get('/actuator/health', { timeout: 10_000 });
    } catch (cause) {
      throw new Error(
        `Real-backend e2e: no backend answering at ${BACKEND_URL} (${cause.message}).\n${HOW_TO_RUN}`
      );
    }
    if (!health.ok()) {
      throw new Error(
        `Real-backend e2e: ${BACKEND_URL}/actuator/health returned ${health.status()}. ` +
          `The app is up but not healthy — usually its database is unreachable.\n${HOW_TO_RUN}`
      );
    }

    // SecurityConfig permits OPTIONS /api/** precisely so MVC's CORS handling answers it: an
    // allowed origin gets 200, an unlisted one 403.
    const preflight = await context.fetch('/api/auth/login', {
      method: 'OPTIONS',
      headers: {
        Origin: FRONTEND_ORIGIN,
        'Access-Control-Request-Method': 'POST',
      },
      failOnStatusCode: false,
    });
    if (preflight.status() === 403) {
      throw new Error(
        `Real-backend e2e: the backend at ${BACKEND_URL} rejects ${FRONTEND_ORIGIN} as an ` +
          'invalid CORS origin, so every login in this suite would fail with a bare 403.\n\n' +
          "  application.yml's app.cors.allowed-origins defaults to ports 5173/5174 only, and " +
          'this suite runs its dev server on its own port. Start the backend with that origin ' +
          'allowed:\n\n' +
          `    APP_CORS_ALLOWED_ORIGINS=${FRONTEND_ORIGIN} java -jar backend/target/glr-hr-backend-*.jar\n\n` +
          '  scripts/start-e2e-backend.mjs sets this for you.\n'
      );
    }

    const probe = await context.post('/api/auth/login', {
      headers: { Origin: FRONTEND_ORIGIN },
      data: { email: PERSONAS.hr.email, password: DEMO_PASSWORD },
      failOnStatusCode: false,
    });

    if (probe.status() === 429) {
      throw new Error(
        `Real-backend e2e: the backend's login rate limiter has locked out ${PERSONAS.hr.email} ` +
          'or this client IP (429).\n\n' +
          '  Usually the aftermath of an earlier misconfigured run: a CORS-rejected or ' +
          'wrong-password login counts as a failure, and app.login-rate-limit locks the account ' +
          'after 5 (or the IP after 20) within the window.\n\n' +
          '  LoginAttemptTracker keeps its counters in memory, so restarting the backend clears ' +
          'them immediately; otherwise wait out app.login-rate-limit.lockout-seconds (900s by ' +
          'default). A successful login also resets both counters.\n'
      );
    }

    if (!probe.ok()) {
      throw new Error(
        `Real-backend e2e: backend at ${BACKEND_URL} is healthy, but logging in as ` +
          `${PERSONAS.hr.email} returned ${probe.status()}. The demo seed ` +
          '(db/migration-demo/V21__demo_seed_accounts.sql) has not been applied — start the ' +
          `backend with SPRING_PROFILES_ACTIVE=demo.\n${HOW_TO_RUN}`
      );
    }
  } finally {
    await context.dispose();
  }
}
