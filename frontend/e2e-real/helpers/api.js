import { request as playwrightRequest } from '@playwright/test';
import { DEMO_PASSWORD, personaFor } from './accounts.js';

// Direct-to-Spring HTTP helpers for the real-backend suite.
//
// These talk to the backend on its own port rather than through the Vite dev proxy: the thing
// under test is the Java service's authorization, and a proxy hop adds a component that can
// only mask a result, never produce one. The browser specs (auth/smoke) go through the proxy
// like a real user does; this module is for asserting what the SERVICE returns.
//
// CSRF: SecurityConfig disables it (`.csrf(AbstractHttpConfigurer::disable)`), so no
// X-XSRF-TOKEN dance is needed here. Session auth is a cookie (GLR_HR_SESSION), which each
// Playwright request context keeps isolated from the others — that isolation is what lets one
// spec hold six concurrent role sessions without them trampling each other.
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
