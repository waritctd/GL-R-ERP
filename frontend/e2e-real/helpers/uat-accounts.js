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
// The UAT seed (V900 + V908) lives ONLY on the `uat` branch. A branch cut from `main` cannot
// import it, diff against it, or verify it — unlike accounts.js, which sits next to the seed it
// mirrors. Two failure modes follow, and only one is caught cheaply:
//
//   • An employee's DIVISION changes → the persona resolves to a different role. CAUGHT: the
//     session capture in global-setup.js asserts `user.role === role` and aborts naming both.
//   • An EMAIL is renamed → a 401, indistinguishable from a wrong password. NOT caught as such,
//     and worse than it sounds: a 401 feeds LoginRateLimitFilter's per-account and per-IP
//     counters, so a stale table here spends the run's rate-limit budget discovering it. That is
//     why global-setup aborts on the FIRST login failure instead of trying all five.
//
// If UAT's seed is re-authored, re-derive this table from
// `git show uat:backend/src/main/resources/db/migration-uat/V900__uat_reference_and_employees.sql`.
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

/** Mirrors db/migration-uat/V900 (+V908) on the `uat` branch. See the header on drift. */
export const UAT_PERSONAS = {
  sales: { email: 'sales@uat.glr', employeeCode: 'GLR-0005', division: 'SA' },
  // SA + a position containing ผู้จัดการ — that position string is the ONLY thing separating this
  // persona from `sales` above, and it is what DivisionAccessPolicy.isManager() keys on.
  sales_manager: { email: 'salesmgr@uat.glr', employeeCode: 'GLR-0007', division: 'SA' },
  import: { email: 'import@uat.glr', employeeCode: 'GLR-0004', division: 'PCIM' },
  ceo: { email: 'ceo@uat.glr', employeeCode: 'GLR-0001', division: 'MD' },
  account: { email: 'account@uat.glr', employeeCode: 'GLR-0013', division: 'AC' },
};

/**
 * The shared UAT persona password, from E2E_UAT_PASSWORD.
 *
 * ⚠️ HONESTY NOTE — this is hygiene, NOT secrecy, and the distinction matters because writing it
 * down as a secret would be a lie a future reader could act on. The value is already published in
 * plaintext on the `uat` branch: in V900's header, in the `htpasswd -nbBC 10` line of
 * V907__uat_clear_forced_password_change.sql, in V908's comment, and in UAT_Accounts.md. This
 * repository is public. Nothing in this file can make it secret, and nobody should believe it has.
 *
 * Keeping it out of the checked-in suite buys two real things and no more:
 *   1. Rotating it is an environment change, not a code change plus a merge.
 *   2. The harness does not add a SECOND copy for someone to find, grep, and mirror again — the
 *      same "a constant mirrored into a second file has no guard" shape this repo keeps hitting.
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
        '  It is the shared password documented for the @uat.glr personas\n' +
        '  (db/migration-uat/V900 and V907, on the `uat` branch).\n\n' +
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
