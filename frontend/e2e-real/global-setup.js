import { mkdirSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { request as playwrightRequest } from '@playwright/test';
import { BACKEND_URL } from './helpers/api.js';
import { DEMO_PASSWORD, PERSONAS } from './helpers/accounts.js';
import {
  UAT_SALES_ROLES,
  authDir,
  statePathFor,
  uatPassword,
  uatPersonaFor,
} from './helpers/uat-accounts.js';

const REMOTE_BASE_URL = (process.env.E2E_BASE_URL || '').trim().replace(/\/$/, '');
const IS_REMOTE = REMOTE_BASE_URL.length > 0;

// The origin the browser will actually send. Locally that is the dev server this config starts;
// against a deployment it is the deployment itself, which is also where /api is served from
// (vercel.json rewrites /api/* to the backend), so browser and API share one origin.
const FRONTEND_ORIGIN = IS_REMOTE
  ? REMOTE_BASE_URL
  : `http://127.0.0.1:${process.env.E2E_REAL_FRONTEND_PORT || 5251}`;

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
  if (IS_REMOTE) return remoteSetup();

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

// ─────────────────────────────────────────────────────────────────────────────────────────────
// REMOTE (deployed UAT) preflight
//
// Same job as the local path — turn a wall of identically-red specs into one actionable message —
// but the failure modes are different, so the checks are too:
//
//   • CORS is NOT exercised. The deployment serves /api from its own origin (vercel.json rewrites
//     /api/* to the backend), so there is no cross-origin request to preflight. Probing it would
//     assert nothing. Do not re-add an OPTIONS check here.
//   • Vercel Deployment Protection is a real and confusing failure: a protected preview answers
//     every request with an HTML SSO challenge, so specs die on JSON parse errors that name
//     nothing. Detected by content-type below and reported by name.
//   • A 429 CANNOT be cleared by restarting anything. LoginAttemptTracker is in-memory on a shared
//     Render service other people are using, so the local advice ("restart the backend") is wrong
//     here and would send someone to do something they cannot do.
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** A run-scoped tag stamped into every row this run creates, so leftovers are findable. */
function newRunId() {
  // Sortable prefix so a human scanning the UAT deal list can tell this morning's leftovers from
  // last month's; random suffix so two concurrent runs (a laptop and CI) never collide.
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z');
  return `E2E-${stamp}-${randomBytes(2).toString('hex')}`;
}

async function remoteSetup() {
  const runId = newRunId();
  process.env.E2E_RUN_ID = runId;
  mkdirSync(authDir(), { recursive: true });

  const anon = await playwrightRequest.newContext({ baseURL: REMOTE_BASE_URL });
  try {
    // 1. Same-origin API reachability. Does more work than the CORS probe it replaces: it proves
    //    the /api rewrite reaches the backend, that a real Spring Security chain answered, and
    //    that we are not looking at an auth challenge page.
    let me;
    try {
      me = await anon.get('/api/auth/me', { failOnStatusCode: false, timeout: 30_000 });
    } catch (cause) {
      throw new Error(
        `UAT e2e: nothing answered at ${REMOTE_BASE_URL}/api/auth/me (${cause.message}).\n\n` +
          '  Check the deployment is up and that E2E_BASE_URL points at the FRONTEND host (which\n' +
          '  rewrites /api/* to the backend), not at the backend directly.\n'
      );
    }

    const contentType = me.headers()['content-type'] ?? '';
    if (!contentType.includes('application/json')) {
      throw new Error(
        `UAT e2e: ${REMOTE_BASE_URL}/api/auth/me answered ${me.status()} with content-type ` +
          `'${contentType}', not JSON.\n\n` +
          "  If that is text/html, this is almost certainly Vercel's Deployment Protection\n" +
          '  challenge rather than the application — a protected preview answers every request\n' +
          '  with an SSO page. Disable Deployment Protection for this preview, or supply a\n' +
          '  bypass token. Without this check the symptom would be every spec failing on a JSON\n' +
          '  parse error that names nothing.\n'
      );
    }
    if (me.status() !== 401) {
      throw new Error(
        `UAT e2e: an ANONYMOUS GET /api/auth/me returned ${me.status()}, expected 401. ` +
          'SecurityConfig is default-deny, so anything else means this is not the app we think ' +
          'it is, or a session leaked into the preflight context.'
      );
    }

    // 2. Anti-mock, structural. This replaces reuseExistingServer:false for remote runs.
    //    A Vite DEV server serves /src/main.jsx and /@vite/client; only `vite build` emits a
    //    hashed /assets/index-*.js. Combined with src/api/index.js throwing under
    //    `PROD && VITE_USE_MOCKS === 'true'`, a document that loads a built bundle provably
    //    cannot be serving mockApi — it would not have booted.
    const doc = await anon.get('/', { failOnStatusCode: false, timeout: 30_000 });
    const html = await doc.text();
    if (!/<script[^>]+src="\/assets\/index-[\w-]+\.js"/.test(html)) {
      throw new Error(
        `UAT e2e: ${REMOTE_BASE_URL} does not look like a production build — no hashed ` +
          '/assets/index-*.js in the served HTML.\n\n' +
          '  This suite\'s protection against asserting mock behaviour under a "real backend"\n' +
          '  name is that a built bundle CANNOT run mocks (src/api/index.js throws on\n' +
          '  PROD + VITE_USE_MOCKS). That reasoning only holds for a real `vite build` output.\n' +
          '  A dev server serves /src/main.jsx instead, and would defeat it.\n'
      );
    }

    // 3. Capture one session per persona. FAIL FAST: on the first login error, stop. Trying the
    //    rest is how a single stale email or wrong password burns the 20-per-IP budget and locks
    //    out real testers for 900s.
    for (const role of UAT_SALES_ROLES) {
      await captureSession(role, runId);
    }
  } finally {
    await anon.dispose();
  }

  // eslint-disable-next-line no-console
  console.log(`[uat] preflight ok — ${UAT_SALES_ROLES.length} sessions captured, run id ${runId}`);
}

async function captureSession(role, runId) {
  const persona = uatPersonaFor(role);
  const context = await playwrightRequest.newContext({ baseURL: REMOTE_BASE_URL });

  try {
    const login = await context.post('/api/auth/login', {
      data: { email: persona.email, password: uatPassword() },
      failOnStatusCode: false,
      timeout: 30_000,
    });

    if (login.status() === 429) {
      throw new Error(
        `UAT e2e: login rate limiter has locked out ${persona.email} or this client IP (429).\n\n` +
          '  ⚠️ You CANNOT clear this by restarting anything. LoginAttemptTracker keeps its\n' +
          '  counters in memory on the shared UAT service, which other people are using.\n\n' +
          '  Either wait out app.login-rate-limit.lockout-seconds (900s by default), or have\n' +
          '  someone log in successfully as that account from this IP — a success resets both\n' +
          '  the per-account and per-IP counters.\n'
      );
    }
    if (!login.ok()) {
      throw new Error(
        `UAT e2e: logging in as ${persona.email} (${persona.employeeCode}) returned ` +
          `${login.status()}.\n\n` +
          '  Aborting before the remaining personas are tried — each failed login spends part of\n' +
          '  a 5-per-account / 20-per-IP budget on a shared deployment.\n\n' +
          '  Likely causes: E2E_UAT_PASSWORD is wrong; or this persona was renamed in the UAT\n' +
          '  seed and helpers/uat-accounts.js is now stale (it is a hand-copy of V900 from the\n' +
          '  `uat` branch — see that file\'s header).\n'
      );
    }

    const { user } = await login.json();
    if (user.role !== role) {
      throw new Error(
        `UAT seed drift: ${persona.email} (${persona.employeeCode}) resolved to role ` +
          `'${user.role}', expected '${role}'.\n\n` +
          '  Roles are DERIVED at login by DivisionAccessPolicy.roleFor() from the employee\'s\n' +
          `  division (expected '${persona.division}') and position — they are never stored. So\n` +
          '  this means UAT\'s employee or division rows have moved under the hand-copied table\n' +
          '  in helpers/uat-accounts.js. Every role assertion below would be meaningless.\n'
      );
    }

    // One authenticated GET before capturing, so CsrfCookieFilter has issued XSRF-TOKEN into this
    // jar. helpers/api.js#writeHeaders reads that cookie off the context and throws without it —
    // a state file captured before it exists would 403 the FIRST write of every spec, and a CSRF
    // 403 looks exactly like an authorization 403 at a glance.
    const verify = await context.get('/api/auth/me', { failOnStatusCode: false });
    if (!verify.ok()) {
      throw new Error(
        `UAT e2e: GET /api/auth/me returned ${verify.status()} immediately after a 200 login as ` +
          `${persona.email}. The session cookie is not surviving the round trip.`
      );
    }

    const state = await context.storageState({ path: statePathFor(role) });
    const names = state.cookies.map((cookie) => cookie.name);
    for (const required of ['GLR_HR_SESSION', 'XSRF-TOKEN']) {
      if (!names.includes(required)) {
        throw new Error(
          `UAT e2e: the captured session for '${role}' has no ${required} cookie ` +
            `(got: ${names.join(', ') || 'none'}).\n\n` +
            '  Over https these cookies carry Secure/SameSite attributes; if they are not landing\n' +
            '  in the storage state, every spec would fail on its first authenticated request\n' +
            '  (missing GLR_HR_SESSION) or its first write (missing XSRF-TOKEN).\n'
        );
      }
    }

    // eslint-disable-next-line no-console
    console.log(`[uat] ${role.padEnd(13)} ${persona.email.padEnd(20)} → ${user.role} (run ${runId})`);
  } finally {
    await context.dispose();
  }
}
