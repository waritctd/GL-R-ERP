import { request as playwrightRequest } from '@playwright/test';
import { DEMO_PASSWORD, personaFor } from './accounts.js';

// Direct-to-Spring HTTP helpers for the real-backend suite.
//
// These talk to the backend on its own port rather than through the Vite dev proxy: the thing
// under test is the Java service's authorization, and a proxy hop adds a component that can
// only mask a result, never produce one. The browser specs (auth/smoke) go through the proxy
// like a real user does; this module is for asserting what the SERVICE returns.
//
// Session auth is a cookie (GLR_HR_SESSION), which each Playwright request context keeps
// isolated from the others — that isolation is what lets one spec hold six concurrent role
// sessions without them trampling each other.
//
// CSRF — this file used to say no X-XSRF-TOKEN dance was needed, on the grounds that
// SecurityConfig calls `.csrf(AbstractHttpConfigurer::disable)`. That is true of SPRING's CSRF
// and false of this application's: `CsrfCookieFilter` (@Order(0)) implements the OWASP
// double-submit pattern by hand, issuing an `XSRF-TOKEN` cookie and requiring it echoed back in
// an `X-XSRF-TOKEN` header on every unsafe method. An authenticated write without that header is
// **403 "Invalid CSRF token"**. Use `writeHeaders()` / the `apiWrite` helpers below for anything
// that is not a GET.
//
// The two filters' ORDER is why the read-only specs never noticed. Spring Security's chain runs
// at order -100, so an ANONYMOUS write is rejected 401 there before CsrfCookieFilter (order 0)
// ever sees it — which is what makes api-surface.spec.js's "anonymous POST ⇒ 401" sweep correct
// as written. CSRF only becomes reachable once a request is authenticated.
export const BACKEND_URL = process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080';

/** A request context with no session — every guarded endpoint must answer 401 to it. */
export function anonApi() {
  return playwrightRequest.newContext({ baseURL: BACKEND_URL });
}

/**
 * Logs in as the seeded persona for `role` and returns its authenticated request context.
 *
 * Throws — rather than returning a half-usable context — if login fails OR if the backend
 * resolves a different role than the one asked for. That second check is the guard against
 * silent seed drift: DivisionAccessPolicy.roleFor derives the role from the employee's
 * division, so a seed edit that moved demo.hr@ into another division would otherwise leave
 * every "hr may do X" assertion below quietly asserting something else's permissions.
 */
export async function apiSessionFor(role) {
  const persona = personaFor(role);
  const context = await playwrightRequest.newContext({ baseURL: BACKEND_URL });
  const response = await context.post('/api/auth/login', {
    data: { email: persona.email, password: DEMO_PASSWORD },
  });

  if (!response.ok()) {
    await context.dispose();
    throw new Error(
      `Real-backend login failed for ${persona.email} (${response.status()}). ` +
        'Is the backend running with SPRING_PROFILES_ACTIVE=demo so V21__demo_seed_accounts.sql applied?'
    );
  }

  const { user } = await response.json();
  if (user.role !== role) {
    await context.dispose();
    throw new Error(
      `Seed drift: ${persona.email} (${persona.employeeCode}) resolved to role '${user.role}', ` +
        `expected '${role}'. DivisionAccessPolicy.roleFor derives this from the employee's ` +
        'division — check V21__demo_seed_accounts.sql before trusting any authz assertion.'
    );
  }

  return context;
}

/** Opens one authenticated context per role, keyed by role. Dispose with `disposeSessions`. */
export async function apiSessionsFor(roles) {
  const entries = await Promise.all(
    roles.map(async (role) => [role, await apiSessionFor(role)])
  );
  return Object.fromEntries(entries);
}

export async function disposeSessions(sessions) {
  await Promise.all(Object.values(sessions ?? {}).map((context) => context.dispose()));
}

// ── Writes ───────────────────────────────────────────────────────────────────

/**
 * The CSRF header a state-changing request needs, read from the context's own cookie jar.
 *
 * Deliberately re-read per call rather than captured at login: `CsrfCookieFilter` issues a fresh
 * token to any caller that arrives without one, so a stale captured value would start failing
 * the moment a context lost or rotated its cookie — and it would fail as a 403, which looks
 * exactly like an authorization result. That confusion is worth designing out of a suite whose
 * whole job is telling real 403s from noise.
 */
export async function writeHeaders(context) {
  const { cookies } = await context.storageState();
  const token = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value;
  if (!token) {
    throw new Error(
      'No XSRF-TOKEN cookie on this request context. CsrfCookieFilter issues one to every ' +
        '/api/** caller, so its absence means no /api request has been made on this context yet.'
    );
  }
  return { 'X-XSRF-TOKEN': token };
}

/**
 * POST/PUT/PATCH/DELETE with the CSRF header attached. Never throws on a non-2xx — the status
 * IS the assertion in most of this suite.
 */
export async function apiWrite(context, method, path, data) {
  const headers = await writeHeaders(context);
  return context.fetch(path, {
    method: method.toUpperCase(),
    headers,
    ...(data === undefined ? {} : { data }),
    failOnStatusCode: false,
  });
}

/** Same request, deliberately WITHOUT the CSRF header — for asserting the filter is live. */
export function apiWriteWithoutCsrf(context, method, path, data) {
  return context.fetch(path, {
    method: method.toUpperCase(),
    ...(data === undefined ? {} : { data }),
    failOnStatusCode: false,
  });
}
