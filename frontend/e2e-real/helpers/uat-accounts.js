// Defines deployed-UAT personas separately from demo-seeded accounts.

/** The five roles the sales pipeline actually needs. Deliberately not all nine. Every request would be a 403
 * that says nothing about the pipeline. Proving they are refused is api-authz.spec.js's job, against a LOCAL
 * stack where a lockout is free. • qc — no persona exists in db/migration-uat at all. */
export const UAT_SALES_ROLES = ['sales', 'import', 'ceo', 'account', 'sales_manager'];

/**
 * Mirrors db/migration-uat/V912__uat_e2e_test_personas.sql on the `uat` branch.
 *
 * NOT V900's @uat.glr personas — those are GONE from the deployed UAT database: no rows match
 * '%@uat.glr' while flyway_schema_history still records V900–V911 as applied, because UAT's
 * hr.employee was rebuilt from PRODUCTION on top of the seed, so no password could ever work.
 *
 * V912 exists so this suite never logs in as a real employee: UAT carries production-derived
 * people, personal gmail/hotmail addresses included. `.invalid` is reserved by RFC 2606
 * and can never receive mail, which is the point.
 */
export const UAT_PERSONAS = {
  sales: { email: 'e2e-sales@e2e.invalid', employeeCode: 'E2E-0001', division: 'SA' },
  // SA + a position containing ผู้จัดการ — that position string is the ONLY thing separating this persona from
  // `sales` above, and it is what DivisionAccessPolicy.isManager() keys on.
  sales_manager: { email: 'e2e-salesmgr@e2e.invalid', employeeCode: 'E2E-0002', division: 'SA' },
  import: { email: 'e2e-import@e2e.invalid', employeeCode: 'E2E-0003', division: 'PCIM' },
  ceo: { email: 'e2e-ceo@e2e.invalid', employeeCode: 'E2E-0005', division: 'MD' },
  account: { email: 'e2e-account@e2e.invalid', employeeCode: 'E2E-0004', division: 'AC' },
};

/** The shared UAT persona password, from E2E_UAT_PASSWORD. ⚠️ THIS ONE IS ACTUALLY A SECRET, unlike the old
 * @uat.glr shared password. V900/V907/V908 published a working UAT password in plaintext in a PUBLIC
 * repository. */
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

// Where captured sessions live ───────────────────────────────────────────────────────────── global-setup.js
// writes one storageState file per persona here; helpers/api.js reads them back. The directory is gitignored:
// these files are LIVE SESSION COOKIES for a shared deployment.
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
