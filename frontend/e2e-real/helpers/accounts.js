// Real-backend e2e personas — mirrors backend/src/main/resources/db/migration-demo/
// V21__demo_seed_accounts.sql, which is the seed the `demo` Spring profile applies
// (application-demo.yml sets spring.flyway.locations to include classpath:db/migration-demo).
//
// These are REAL rows in hr.employee with a real BCrypt password_hash — logging in as one of
// them goes through AuthService.login → EmployeeAuthRepository → Postgres, not a fixture.
//
// The `role` field below is NOT configured anywhere: DivisionAccessPolicy.roleFor derives it
// from the employee's division (and position, for the manager split) at login time. Asserting
// it is therefore a real assertion about that policy running against real seeded rows — see
// auth.spec.js's "derives each persona's role from its division" test.

// V21: every demo account shares this password; the BCrypt hash in the seed was generated for it.
export const DEMO_PASSWORD = 'Demo@2026';

// Keyed by the role the backend resolves for them, because that is what every authz assertion
// in this suite keys on. `email` is the login identity; `employeeCode` is the seed row.
export const PERSONAS = {
  employee: {
    email: 'demo.employee@demo.invalid',
    employeeCode: 'DEMO-EMP01',
    // No division at all in the seed (division_code NULL) → roleFor's fallthrough → 'employee'.
    division: null,
  },
  hr: {
    email: 'demo.hr@demo.invalid',
    employeeCode: 'DEMO-HR01',
    division: 'HR',
  },
  sales: {
    email: 'demo.sales@demo.invalid',
    employeeCode: 'DEMO-SLS01',
    division: 'SA',
  },
  sales_manager: {
    // SA division + a position containing "ผู้จัดการ" — the manager half of roleFor's
    // SA branch (DivisionAccessPolicy.isManager), which is why this persona resolves to
    // sales_manager while demo.sales (same division, DEMO-STAFF position) resolves to sales.
    email: 'demo.salesmanager@demo.invalid',
    employeeCode: 'DEMO-MGR01',
    division: 'SA',
  },
  import: {
    email: 'demo.import@demo.invalid',
    employeeCode: 'DEMO-IMP01',
    division: 'PCIM',
  },
  ceo: {
    email: 'demo.ceo@demo.invalid',
    employeeCode: 'DEMO-CEO01',
    division: 'MD',
  },
  // The three below were the suite's long-standing coverage gap, closed by
  // V139__demo_missing_role_personas_and_hire_dates.sql. Until that seed existed there was no
  // real login in AC / WH / QC at all, so every sweep here ran six roles wide and said nothing
  // whatever about these three.
  account: {
    // The sharp edge of that gap: `account` is in TicketAccessPolicy.VIEWER_ROLES and is the
    // only role permitted to confirm a payment, and no automated test had ever driven it.
    email: 'demo.account@demo.invalid',
    employeeCode: 'DEMO-ACC01',
    division: 'AC',
  },
  warehouse: {
    email: 'demo.warehouse@demo.invalid',
    employeeCode: 'DEMO-WH01',
    division: 'WH',
  },
  qc: {
    // Division source_code 'QC', not the 'QC&ISO' name — DivisionAccessPolicy compares the
    // lowercased source_code exactly, so a row coded 'QC&ISO' would resolve to plain employee.
    email: 'demo.qc@demo.invalid',
    employeeCode: 'DEMO-QC01',
    division: 'QC',
  },
};

// The roles this suite can actually exercise against a real backend.
//
// This is now all nine roles DivisionAccessPolicy.roleFor can return, so a green run covers the
// whole role matrix rather than two thirds of it. If roleFor ever grows a tenth branch, add the
// persona to V139's pattern and to PERSONAS above — auth.spec.js asserts the backend derives each
// role from its division, so an unseeded role is a silent hole, not a failing test.
export const REAL_ROLES = Object.keys(PERSONAS);

export function personaFor(role) {
  const persona = PERSONAS[role];
  if (!persona) {
    throw new Error(
      `No real-backend persona seeded for role '${role}'. Seeded roles: ${REAL_ROLES.join(', ')}.`
    );
  }
  return persona;
}
