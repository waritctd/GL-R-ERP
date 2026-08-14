// UAT personas — the accounts the deployed-UAT run logs in as.
//
// ── Why this is not part of accounts.js ──────────────────────────────────────────────────────
// accounts.js is documented end to end as a mirror of `db/migration-demo/V21__demo_seed_accounts.sql`,
// and its DEMO_PASSWORD constant is load-bearing for auth.js and api.js. Widening it to carry a
// second seed's accounts would leave neither file honest about which seed it mirrors, and the two
// seeds are mutually exclusive at runtime — application-uat.yml sets
// `flyway.locations: db/migration,db/migration-uat`, so a UAT database has never had V21 applied
// and none of the @demo.invalid accounts exist there.
//
// ── This table is a HAND-COPY, and that is a real risk ───────────────────────────────────────
// The UAT seed (V912) lives ONLY on the `uat` branch. A branch cut from `main` cannot import it,
// diff against it, or verify it — unlike accounts.js, which sits next to the seed it mirrors.
// Two failure modes follow, and only one is caught cheaply:
//
//   • An employee's DIVISION changes → the persona resolves to a different role. CAUGHT: the
//     session capture in global-setup.js asserts `user.role === role` and aborts naming both.
//   • An EMAIL is renamed → a 401, indistinguishable from a wrong password. NOT caught as such,
//     and worse than it sounds: a 401 feeds LoginRateLimitFilter's per-account and per-IP
//     counters, so a stale table here spends the run's rate-limit budget discovering it. That is
//     why global-setup aborts on the FIRST login failure instead of trying all five.
//
// If UAT's seed is re-authored, re-derive this table from
// `git show uat:backend/src/main/resources/db/migration-uat/V912__uat_e2e_test_personas.sql`.
//
// ── Roles are DERIVED, never stored ──────────────────────────────────────────────────────────
// DivisionAccessPolicy.roleFor() computes the role at login from the employee's division code and
// position, in a strict precedence ladder (md/กรรมการ → ceo, hr, pcim → import, ac → account,
// wh → warehouse, qc, sa → sales_manager if the position contains ผู้จัดการ else sales, else
// employee). The `division` column below is therefore the CAUSE of the role, not a label for it.

/**
 * The five roles the sales pipeline actually needs. Deliberately not all nine.
 *
 * Excluded, with reasons — every login spends a slice of a 20-per-IP / 900s budget on a shared
 * deployment, so "log them all in just in case" has a real cost paid by real testers:
 *
 *   • hr, employee, warehouse — not in TicketAccessPolicy.VIEWER_ROLES, so they cannot read a
 *     deal at all. Every request would be a 403 that says nothing about the pipeline. Proving
 *     they are refused is api-authz.spec.js's job, against a LOCAL stack where a lockout is free.
 *   • qc — no persona exists in db/migration-uat at all. UAT seeds divisions MD, HR, PCIM, SA,
 *     WH, PD, AC, GA, IT, ADMIN and no QC; the QC staff sit in division PD, which maps to
 *     `employee`. Attempting it is a guaranteed 401, i.e. a fifth of the per-account budget spent
 *     for zero coverage.
 *   • admin@uat.glr — logs in fine but resolves to plain `employee`. DivisionAccessPolicy has no
 *     admin branch; `admin` is not a derivable role in this application.
 */
export const UAT_SALES_ROLES = ['sales', 'import', 'ceo', 'account', 'sales_manager'];

/**
 * Mirrors db/migration-uat/V912__uat_e2e_test_personas.sql on the `uat` branch.
 *
 * NOT V900's @uat.glr personas — those are GONE from the deployed UAT database. Measured
 * 2026-08-15: zero rows match '%@uat.glr' while flyway_schema_history still records V900–V911 as
 * applied, because UAT's hr.employee was rebuilt from PRODUCTION on top of the seed. This table
 * previously named them, and no password could ever have worked.
 *
 * V912 exists so this suite never logs in as a real employee: UAT now carries 207 production-
 * derived people, personal gmail/hotmail addresses included. `.invalid` is reserved by RFC 2606
 * and can never receive mail, which is the point.
 */
export const UAT_PERSONAS = {
  sales: { email: 'e2e-sales@e2e.invalid', employeeCode: 'E2E-0001', division: 'SA' },
  // SA + a position containing ผู้จัดการ — that position string is the ONLY thing separating this
  // persona from `sales` above, and it is what DivisionAccessPolicy.isManager() keys on. V912's
  // own final DO block asserts this derives `sales_manager` and refuses to seed if it does not:
  // a sales rep silently promoted to manager would invert every "a plain rep cannot" assertion.
  sales_manager: { email: 'e2e-salesmgr@e2e.invalid', employeeCode: 'E2E-0002', division: 'SA' },
  import: { email: 'e2e-import@e2e.invalid', employeeCode: 'E2E-0003', division: 'PCIM' },
  ceo: { email: 'e2e-ceo@e2e.invalid', employeeCode: 'E2E-0005', division: 'MD' },
  account: { email: 'e2e-account@e2e.invalid', employeeCode: 'E2E-0004', division: 'AC' },
};

/**
 * The shared UAT persona password, from E2E_UAT_PASSWORD.
 *
 * ⚠️ THIS ONE IS ACTUALLY A SECRET, unlike the old @uat.glr shared password.
 *
 * That distinction is the whole reason V912 exists in the shape it does. V900/V907/V908 published
 * a working UAT password in plaintext in a PUBLIC repository. That was survivable only while UAT
 * held synthetic data; it stopped being survivable when UAT was rebuilt from production and began
 * holding 207 real people's records. V912 therefore seeds its personas with password_hash NULL —
 * they authenticate to nothing until someone sets one by hand — and that value is never committed
 * anywhere.
 *
 * So do not default this function, and do not "restore" a published value into it. A default
 * converts "someone forgot the env var" into a silent login attempt, spending the 5-per-account /
 * 20-per-IP budget to discover it; a committed value hands anyone with repo access a session on a
 * database full of personal data. An explicit error costs nothing and says what to do.
 */
export function uatPassword() {
  const value = process.env.E2E_UAT_PASSWORD;
  if (!value) {
    throw new Error(
      'E2E_UAT_PASSWORD is not set.\n\n' +
        '  The UAT run logs in as real accounts on a SHARED, LONG-LIVED deployment, so this suite\n' +
        '  does not carry the password. Supply it at the call site:\n\n' +
        '      E2E_BASE_URL=https://<uat-frontend-host> \\\n' +
        '      E2E_UAT_PASSWORD=... \\\n' +
        '      npm run test:e2e:uat\n\n' +
        '  This is the password set on the E2E-* personas that V912 seeds. It is deliberately\n' +
        '  NOT in the repository and never will be: V912 leaves password_hash NULL, and whoever\n' +
        '  runs UAT sets it once by hand (see that migration\'s header for the UPDATE). A working\n' +
        '  credential committed here would be a public access path to a database holding\n' +
        '  production-derived personal data.\n\n' +
        '  If you do not have it, ask whoever runs UAT -- do not try the values published in\n' +
        '  V900/V907 for the old @uat.glr personas. Those accounts no longer exist in that\n' +
        '  database, so every attempt is a guaranteed 401 that spends the budget below.\n\n' +
        '  ⚠️ Do NOT guess it. LoginRateLimitFilter counts 5 failures per account and 20 per\n' +
        '  client IP inside a 900-second window, counting 401s AND 403s. A lockout on UAT hits\n' +
        '  real testers and cannot be cleared by restarting anything you control — the tracker is\n' +
        '  in-memory on a shared Render service.\n'
    );
  }
  return value;
}

// ── Where captured sessions live ─────────────────────────────────────────────────────────────
// global-setup.js writes one storageState file per persona here; helpers/api.js reads them back.
// Both constants live in THIS module rather than in either of those, because global-setup imports
// api.js — putting them in api.js would make the dependency circular.
//
// The directory is gitignored: these files are LIVE SESSION COOKIES for a shared deployment.
// Deliberately not under test-results-real/ — Playwright clears outputDir around a run, and
// globalSetup writing into a directory Playwright is about to sweep is a race worth not having.
const AUTH_DIR = new URL('../.auth/', import.meta.url);

export function authDir() {
  return AUTH_DIR;
}

export function statePathFor(role) {
  return new URL(`uat-${role}.json`, AUTH_DIR).pathname;
}

/** @param {string} role one of UAT_SALES_ROLES */
export function uatPersonaFor(role) {
  const persona = UAT_PERSONAS[role];
  if (!persona) {
    throw new Error(
      `No UAT persona for role '${role}'. Seeded and usable here: ${Object.keys(UAT_PERSONAS).join(', ')}. ` +
        'Note there is NO `qc` persona in db/migration-uat, and admin@uat.glr resolves to plain ' +
        '`employee` because DivisionAccessPolicy has no admin branch. See this file\'s header for ' +
        'why hr/employee/warehouse are deliberately absent.'
    );
  }
  return persona;
}
