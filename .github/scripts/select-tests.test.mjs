// Contract tests for select-tests.mjs. Run with: node --test .github/scripts/
//
// Written BEFORE the implementation (test-first standing rule). Fixtures stand in for the
// `git diff --name-only` output and the on-disk Java sources the phase-2 CLI will read.

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { selectBackendTests, selectFrontendTests } from './select-tests.mjs';

// ── Fixtures ────────────────────────────────────────────────────────────────────────────

const MAIN = 'backend/src/main/java/th/co/glr/hr';
const TEST = 'backend/src/test/java/th/co/glr/hr';

function src(path, content = '') {
  return { path, content };
}

// Dependency graph in this fixture (arrow = "imports"):
//   leave     → employee
//   payroll   → leave            (explicit import)
//   dashboard → leave            (wildcard import, from a TEST source)
//   ticket    → (nothing)        (the wrong-way-round control)
//   employee  → (nothing)
const JAVA_SOURCES = [
  src(`${MAIN}/employee/EmployeeService.java`,
    'package th.co.glr.hr.employee;\npublic class EmployeeService {}\n'),
  src(`${MAIN}/leave/LeaveService.java`,
    'package th.co.glr.hr.leave;\nimport th.co.glr.hr.employee.EmployeeService;\npublic class LeaveService {}\n'),
  src(`${MAIN}/payroll/PayrollService.java`,
    'package th.co.glr.hr.payroll;\nimport th.co.glr.hr.leave.LeaveService;\npublic class PayrollService {}\n'),
  src(`${TEST}/dashboard/DashboardServiceTest.java`,
    'package th.co.glr.hr.dashboard;\nimport th.co.glr.hr.leave.*;\nclass DashboardServiceTest {}\n'),
  src(`${MAIN}/ticket/TicketService.java`,
    'package th.co.glr.hr.ticket;\nimport th.co.glr.hr.customer.Customer;\npublic class TicketService {}\n'),
  src(`${MAIN}/customer/Customer.java`,
    'package th.co.glr.hr.customer;\npublic class Customer {}\n'),
  // Slash-form readers (F2): tests that open another package's SOURCE FILES by path, the way
  // attendance/daily/LatenessNeverAffectsPayrollTest.java really does.
  src(`${TEST}/attendance/daily/LatenessNeverAffectsPayrollTest.java`,
    'package th.co.glr.hr.attendance.daily;\nList.of("src/main/java/th/co/glr/hr/payroll", "src/main/java/th/co/glr/hr/commission");\n'),
  src(`${MAIN}/commission/CommissionCalculator.java`,
    'package th.co.glr.hr.commission;\npublic class CommissionCalculator {}\n'),
  src(`${TEST}/support/AbstractPostgresIntegrationTest.java`,
    'package th.co.glr.hr.support;\npublic abstract class AbstractPostgresIntegrationTest {}\n'),
  src(`${TEST}/FlywayMigrationTest.java`,
    'package th.co.glr.hr;\nclass FlywayMigrationTest {}\n'),
];

function backend(overrides = {}) {
  return selectBackendTests({
    changedFiles: [],
    javaSources: JAVA_SOURCES,
    isPullRequest: true,
    labels: [],
    ...overrides,
  });
}

// {path, content}: the picker must see the CONTENT because some of these read backend Java
// files by path (F9), and only a content match can tie a backend change to them.
const FS_READING_TESTS = [
  { path: 'src/baseLayer.test.js', content: "const css = readFileSync('src/styles.css', 'utf8');" },
  { path: 'src/features/payroll/payrollResponsiveCss.test.js', content: "readFileSync(resolve('src/index.css'))" },
  { path: 'src/features/tickets/stageCatalog.test.js',
    content: "const JAVA_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/ticket/DealStage.java';\nreadFileSync(JAVA_RELATIVE_PATH)" },
  { path: 'src/features/tickets/dealTrackingMeta.test.js',
    content: "// mirrors WinProbabilityDefaults.java\nreadFileSync('backend/src/main/java/th/co/glr/hr/ticket/WinProbabilityDefaults.java')" },
];
const FS_READING_PATHS = FS_READING_TESTS.map((t) => t.path).sort();
const SERVER_CONTRACT = 'src/api/serverContract.test.js';

function frontend(overrides = {}) {
  return selectFrontendTests({
    changedFiles: [],
    testFilesReadingFs: FS_READING_TESTS,
    isPullRequest: true,
    labels: [],
    ...overrides,
  });
}

// Surefire's default includes, which `-Dtest` REPLACES rather than narrows, so the pattern
// must restate them per package.
function surefireIncludesFor(pkg) {
  const base = `th/co/glr/hr/${pkg}/**`;
  return [`${base}/Test*.java`, `${base}/*Test.java`, `${base}/*Tests.java`, `${base}/*TestCase.java`];
}

// ── selectBackendTests ──────────────────────────────────────────────────────────────────

describe('selectBackendTests — full-suite triggers', () => {
  it('push event (not a pull request) → full, regardless of the diff', () => {
    const r = backend({ isPullRequest: false, changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.equal(r.mode, 'full');
    assert.match(r.reason, /push|pull request/i);
  });

  it('full-ci label on a PR → full, regardless of the diff', () => {
    const r = backend({ labels: ['full-ci'], changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.equal(r.mode, 'full');
    assert.match(r.reason, /full-ci/);
  });

  it('F1: a PR whose base branch is main → full (merging to main deploys)', () => {
    const r = backend({ baseRef: 'main', changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.equal(r.mode, 'full');
    assert.match(r.reason, /main/);
  });

  it('F1 wrong-way-round: a PR into develop stays selective', () => {
    const r = backend({ baseRef: 'develop', changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.equal(r.mode, 'selected');
  });

  it('an unrelated label does NOT force full', () => {
    const r = backend({ labels: ['needs-review'], changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.equal(r.mode, 'selected');
  });

  const fullTriggers = [
    ['pom.xml', 'backend/pom.xml'],
    ['mvnw wrapper script', 'backend/mvnw'],
    ['mvnw.cmd wrapper script', 'backend/mvnw.cmd'],
    ['.mvn wrapper config', 'backend/.mvn/wrapper/maven-wrapper.properties'],
    ['Flyway migration under main resources', 'backend/src/main/resources/db/migration/V999__anything.sql'],
    ['application yml under main resources', 'backend/src/main/resources/application-test.yml'],
    ['template under main resources', 'backend/src/main/resources/templates/quotation.html'],
    ['test resources', 'backend/src/test/resources/application-test.yml'],
    ['test support package', `${TEST}/support/AbstractPostgresIntegrationTest.java`],
    ['main config package', `${MAIN}/config/SecurityConfig.java`],
    ['main config sub-package', `${MAIN}/config/web/CorsConfig.java`],
    ['main common package', `${MAIN}/common/ApiError.java`],
    ['test common package (owner ruling 2026-10-01: same as main common)', `${TEST}/common/JsonAssertions.java`],
    ['top-level java in th/co/glr/hr (main)', `${MAIN}/HrBackendApplication.java`],
    ['top-level java in th/co/glr/hr (test)', `${TEST}/FlywayMigrationTest.java`],
    ['backend-ci workflow file', '.github/workflows/backend-ci.yml'],
    ['the selection script itself', '.github/scripts/select-tests.mjs'],
    ['anything else under .github/scripts', '.github/scripts/helper.sh'],
    ['unknown backend path (fail-safe)', 'backend/scripts/build-something.sh'],
    ['java outside th/co/glr/hr (fail-safe)', 'backend/src/main/java/com/other/Thing.java'],
  ];

  for (const [label, path] of fullTriggers) {
    it(`${label} changed → full  (${path})`, () => {
      // Mixed with an ordinary package change so the trigger, not emptiness, decides.
      const r = backend({ changedFiles: [`${MAIN}/leave/LeaveService.java`, path] });
      assert.equal(r.mode, 'full', `expected full for ${path}, got ${r.mode} (${r.reason})`);
      assert.ok(r.reason.includes(path), `reason should name the trigger path: ${r.reason}`);
    });
  }
});

describe('selectBackendTests — nothing to run', () => {
  it('empty diff → none', () => {
    const r = backend({ changedFiles: [] });
    assert.equal(r.mode, 'none');
    assert.deepEqual(r.packages, []);
    assert.equal(r.testPattern, '');
  });

  it('frontend-only diff → none', () => {
    const r = backend({ changedFiles: ['frontend/src/App.jsx', 'frontend/package-lock.json'] });
    assert.equal(r.mode, 'none');
  });

  it('docs-only diff at the repo root → none', () => {
    const r = backend({ changedFiles: ['README.md', 'CLAUDE.md', 'docs/api/api-surface.json'] });
    assert.equal(r.mode, 'none');
  });

  it('ignorable backend files only (markdown, Dockerfile) → none', () => {
    const r = backend({ changedFiles: ['backend/README.md', 'backend/docs/notes.md', 'backend/Dockerfile'] });
    assert.equal(r.mode, 'none');
  });

  it('F7: a .md inside main resources is on the classpath → full, not ignorable', () => {
    const r = backend({ changedFiles: ['backend/src/main/resources/templates/README.md'] });
    assert.equal(r.mode, 'full');
    assert.ok(r.reason.includes('backend/src/main/resources/templates/README.md'));
  });

  it('F7: a .md inside test resources → full', () => {
    const r = backend({ changedFiles: ['backend/src/test/resources/fixtures/NOTES.md'] });
    assert.equal(r.mode, 'full');
  });

  it('F6: a rename out of common/ (seen as delete + add with --no-renames) → full', () => {
    const r = backend({ changedFiles: [`${MAIN}/common/Money.java`, `${MAIN}/leave/Money.java`] });
    assert.equal(r.mode, 'full');
  });

  it('ignorable backend files alongside a package change do not escalate to full', () => {
    const r = backend({ changedFiles: ['backend/Dockerfile', `${MAIN}/employee/EmployeeService.java`] });
    assert.equal(r.mode, 'selected');
  });
});

describe('selectBackendTests — package selection', () => {
  it('single main-package change → that package + config + support, and always Flyway', () => {
    const r = backend({ changedFiles: [`${MAIN}/employee/EmployeeService.java`] });
    assert.equal(r.mode, 'selected');
    // employee is changed; leave imports employee (one reverse level). payroll/dashboard
    // import leave, NOT employee, so a second level is deliberately not followed.
    assert.deepEqual(r.packages, ['config', 'employee', 'leave', 'support']);
    assert.ok(r.testPattern.includes('th/co/glr/hr/FlywayMigrationTest.java'));
  });

  it('a change to a TEST source contributes its package too', () => {
    const r = backend({ changedFiles: [`${TEST}/customer/CustomerRepositoryTest.java`] });
    assert.equal(r.mode, 'selected');
    assert.ok(r.packages.includes('customer'));
    // ticket imports customer → pulled in by the reverse dependency rule.
    assert.ok(r.packages.includes('ticket'));
  });

  it('reverse dependency: a package that imports the changed one IS selected', () => {
    const r = backend({ changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.ok(r.packages.includes('payroll'), `payroll imports leave; got ${r.packages}`);
  });

  it('wildcard import (import th.co.glr.hr.leave.*) counts as a reverse dependency', () => {
    const r = backend({ changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.ok(r.packages.includes('dashboard'), `dashboard has a wildcard import of leave; got ${r.packages}`);
  });

  it('reverse dependency is found in TEST sources, not only main', () => {
    // dashboard's only reference to leave lives in a test class (see fixture).
    const r = backend({ changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.ok(r.packages.includes('dashboard'));
  });

  it('wrong-way-round: a package the changed one imports is NOT selected', () => {
    // leave → employee. Changing leave must not drag employee in (nothing in employee
    // references leave, so employee tests cannot be affected).
    const r = backend({ changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.ok(!r.packages.includes('employee'), `employee must not be selected; got ${r.packages}`);
  });

  it('wrong-way-round: an unrelated package is NOT selected', () => {
    const r = backend({ changedFiles: [`${MAIN}/leave/LeaveService.java`] });
    assert.ok(!r.packages.includes('ticket'), `ticket must not be selected; got ${r.packages}`);
    assert.ok(!r.packages.includes('customer'), `customer must not be selected; got ${r.packages}`);
  });

  it('only ONE level of reverse dependencies is followed', () => {
    // employee ← leave ← payroll. Changing employee selects leave but not payroll.
    const r = backend({ changedFiles: [`${MAIN}/employee/EmployeeService.java`] });
    assert.ok(r.packages.includes('leave'));
    assert.ok(!r.packages.includes('payroll'), `payroll is two hops away; got ${r.packages}`);
    assert.ok(!r.packages.includes('dashboard'), `dashboard is two hops away; got ${r.packages}`);
  });

  it('F2: slash-form path reference (th/co/glr/hr/<pkg>) is a reverse dependency', () => {
    // LatenessNeverAffectsPayrollTest reads commission SOURCE FILES by path, no import.
    const r = backend({ changedFiles: [`${MAIN}/commission/CommissionCalculator.java`] });
    assert.ok(r.packages.includes('attendance'), `attendance reads commission by path; got ${r.packages}`);
  });

  it('F2: slash-form followed by a further path segment also counts', () => {
    const sources = [
      src(`${MAIN}/pricing/PricingService.java`, 'package th.co.glr.hr.pricing;\n'),
      src(`${TEST}/finance/FinanceGuardTest.java`,
        'package th.co.glr.hr.finance;\nPath.of("src/main/java/th/co/glr/hr/pricing/PricingService.java");\n'),
    ];
    const r = backend({ javaSources: sources, changedFiles: [`${MAIN}/pricing/PricingService.java`] });
    assert.ok(r.packages.includes('finance'), `got ${r.packages}`);
  });

  it('F2 wrong-way-round: slash-form th/co/glr/hr/pricingrequest does NOT match pricing', () => {
    const sources = [
      src(`${MAIN}/pricing/PricingService.java`, 'package th.co.glr.hr.pricing;\n'),
      src(`${TEST}/finance/FinanceGuardTest.java`,
        'package th.co.glr.hr.finance;\nPath.of("src/main/java/th/co/glr/hr/pricingrequest");\n'),
    ];
    const r = backend({ javaSources: sources, changedFiles: [`${MAIN}/pricing/PricingService.java`] });
    assert.deepEqual(r.packages, ['config', 'pricing', 'support']);
  });

  it('a same-package substring is not mistaken for a different package', () => {
    // "pricing" vs "pricingrequest": a source importing th.co.glr.hr.pricingrequest.X must
    // not be treated as a dependant of th.co.glr.hr.pricing.
    const sources = [
      src(`${MAIN}/pricing/PricingService.java`, 'package th.co.glr.hr.pricing;\n'),
      src(`${MAIN}/finance/FinanceService.java`,
        'package th.co.glr.hr.finance;\nimport th.co.glr.hr.pricingrequest.PricingRequest;\n'),
    ];
    const r = backend({ javaSources: sources, changedFiles: [`${MAIN}/pricing/PricingService.java`] });
    assert.deepEqual(r.packages, ['config', 'pricing', 'support']);
  });

  it('full mode still reports the always-on packages as empty selection (pattern unused)', () => {
    const r = backend({ isPullRequest: false });
    assert.equal(r.mode, 'full');
    assert.equal(r.testPattern, '');
  });
});

describe('selectBackendTests — testPattern format', () => {
  it('mirrors surefire default includes per package, sorted, plus FlywayMigrationTest', () => {
    const sources = [src(`${MAIN}/employee/EmployeeService.java`, 'package th.co.glr.hr.employee;\n')];
    const r = backend({ javaSources: sources, changedFiles: [`${MAIN}/employee/EmployeeService.java`] });
    const expected = [
      ...surefireIncludesFor('config'),
      ...surefireIncludesFor('employee'),
      ...surefireIncludesFor('support'),
      'th/co/glr/hr/FlywayMigrationTest.java',
    ].join(',');
    assert.equal(r.testPattern, expected);
  });

  it('is deterministic and deduped when several files in one package change', () => {
    const changedFiles = [
      `${MAIN}/employee/EmployeeService.java`,
      `${MAIN}/employee/EmployeeController.java`,
      `${TEST}/employee/EmployeeServiceTest.java`,
    ];
    const a = backend({ changedFiles });
    const b = backend({ changedFiles: [...changedFiles].reverse() });
    assert.deepEqual(a, b);
    assert.deepEqual(a.packages, [...new Set(a.packages)].sort());
    assert.equal(a.testPattern.split(',').filter((p) => p.includes('/employee/')).length, 4);
  });

  it('does not mutate its inputs', () => {
    const changedFiles = [`${MAIN}/leave/LeaveService.java`, `${MAIN}/employee/EmployeeService.java`];
    const copy = [...changedFiles];
    backend({ changedFiles });
    assert.deepEqual(changedFiles, copy);
  });
});

// ── selectFrontendTests ─────────────────────────────────────────────────────────────────

describe('selectFrontendTests — full-suite triggers', () => {
  it('push event → full', () => {
    const r = frontend({ isPullRequest: false, changedFiles: ['frontend/src/App.jsx'] });
    assert.equal(r.mode, 'full');
    assert.deepEqual(r.extraTestFiles, []);
  });

  it('F1: a PR whose base branch is main → full', () => {
    const r = frontend({ baseRef: 'main', changedFiles: ['frontend/src/App.jsx'] });
    assert.equal(r.mode, 'full');
    assert.match(r.reason, /main/);
  });

  it('F1 wrong-way-round: a PR into develop stays in changed mode', () => {
    const r = frontend({ baseRef: 'develop', changedFiles: ['frontend/src/App.jsx'] });
    assert.equal(r.mode, 'changed');
  });

  it('full-ci label → full', () => {
    const r = frontend({ labels: ['full-ci'], changedFiles: ['frontend/src/App.jsx'] });
    assert.equal(r.mode, 'full');
    assert.match(r.reason, /full-ci/);
  });

  const fullTriggers = [
    ['package.json', 'frontend/package.json'],
    ['package-lock.json', 'frontend/package-lock.json'],
    ['vite config', 'frontend/vite.config.js'],
    ['vitest config', 'frontend/vitest.config.js'],
    ['vitest setup file', 'frontend/src/test/setup.js'],
    ['test fixtures dir', 'frontend/src/test/fixtures/employees.json'],
    ['.env.development', 'frontend/.env.development'],
    ['.env.example', 'frontend/.env.example'],
    ['frontend-ci workflow file', '.github/workflows/frontend-ci.yml'],
    ['the selection script itself', '.github/scripts/select-tests.mjs'],
    ['global CSS (read by tests via fs, invisible to the import graph)', 'frontend/src/styles.css'],
    ['tokens CSS', 'frontend/src/index.css'],
    ['a non-JS asset under frontend', 'frontend/public/favicon.svg'],
    ['a non-JS file in frontend root', 'frontend/index.html'],
  ];

  for (const [label, path] of fullTriggers) {
    it(`${label} changed → full  (${path})`, () => {
      const r = frontend({ changedFiles: ['frontend/src/App.jsx', path] });
      assert.equal(r.mode, 'full', `expected full for ${path}, got ${r.mode} (${r.reason})`);
      assert.ok(r.reason.includes(path), `reason should name the trigger path: ${r.reason}`);
    });
  }
});

describe('selectFrontendTests — changed mode', () => {
  it('JS-only source change → changed, with every fs-reading test as an extra', () => {
    const r = frontend({ changedFiles: ['frontend/src/features/leave/LeavePage.jsx'] });
    assert.equal(r.mode, 'changed');
    assert.deepEqual(r.extraTestFiles, FS_READING_PATHS);
  });

  it('JS/JSX/TS/TSX/JSON changes all stay in changed mode', () => {
    const r = frontend({
      changedFiles: [
        'frontend/src/a.js',
        'frontend/src/b.jsx',
        'frontend/src/c.ts',
        'frontend/src/d.tsx',
        'frontend/audit-allowlist.json',
      ],
    });
    assert.equal(r.mode, 'changed');
  });

  it('a JS test file change (not under src/test) → changed, not full', () => {
    const r = frontend({ changedFiles: ['frontend/src/api/contract.test.js'] });
    assert.equal(r.mode, 'changed');
  });

  it('wrong-way-round: serverContract test is NOT added for a plain frontend change', () => {
    const r = frontend({ changedFiles: ['frontend/src/features/leave/LeavePage.jsx'] });
    assert.ok(!r.extraTestFiles.includes(SERVER_CONTRACT));
  });

  it('a *Controller.java change adds serverContract test (backend-only diff)', () => {
    const r = frontend({ changedFiles: [`${MAIN}/leave/LeaveController.java`] });
    assert.equal(r.mode, 'changed');
    assert.ok(r.extraTestFiles.includes(SERVER_CONTRACT), `got ${r.extraTestFiles}`);
  });

  it('a docs/api/** change adds serverContract test', () => {
    const r = frontend({ changedFiles: ['docs/api/api-surface.json'] });
    assert.equal(r.mode, 'changed');
    assert.ok(r.extraTestFiles.includes(SERVER_CONTRACT), `got ${r.extraTestFiles}`);
  });

  it('serverContract extra is deduped when it is also an fs-reading test', () => {
    const r = frontend({
      changedFiles: ['docs/api/api-surface.json'],
      testFilesReadingFs: [...FS_READING_TESTS, { path: SERVER_CONTRACT, content: 'readFileSync("docs/api/api-surface.json")' }],
    });
    assert.equal(r.extraTestFiles.filter((f) => f === SERVER_CONTRACT).length, 1);
  });

  it('F9: a backend Java file read by an fs-reading test → changed, that test in the extras', () => {
    const r = frontend({ changedFiles: [`${MAIN}/ticket/DealStage.java`] });
    assert.equal(r.mode, 'changed');
    assert.ok(r.extraTestFiles.includes('src/features/tickets/stageCatalog.test.js'), `got ${r.extraTestFiles}`);
  });

  it('F9: only the tests that reference the changed Java file are added — not every fs reader', () => {
    const r = frontend({ changedFiles: [`${MAIN}/ticket/DealStage.java`] });
    assert.deepEqual(r.extraTestFiles, ['src/features/tickets/stageCatalog.test.js']);
  });

  it('F9: a basename mention (WinProbabilityDefaults.java) is enough to tie the test in', () => {
    // Own fixture that names ONLY the basename — the shared fixture also carries the full
    // path, so the path match would pass this test even with the basename rule removed.
    const readers = [{ path: 'src/features/tickets/winProbability.test.js',
      content: "// mirrors WinProbabilityDefaults.java\nreadFileSync(javaPath('WinProbabilityDefaults.java'))" }];
    const r = frontend({ changedFiles: [`${MAIN}/ticket/WinProbabilityDefaults.java`], testFilesReadingFs: readers });
    assert.deepEqual(r.extraTestFiles, ['src/features/tickets/winProbability.test.js']);
  });

  it('F9: a referenced Controller gets both the serverContract test and its reader', () => {
    const readers = [...FS_READING_TESTS,
      { path: 'src/features/leave/leaveRoutes.test.js', content: "readFileSync('backend/src/main/java/th/co/glr/hr/leave/LeaveController.java')" }];
    const r = frontend({ changedFiles: [`${MAIN}/leave/LeaveController.java`], testFilesReadingFs: readers });
    assert.equal(r.mode, 'changed');
    assert.deepEqual(r.extraTestFiles, [SERVER_CONTRACT, 'src/features/leave/leaveRoutes.test.js']);
  });

  it('extraTestFiles are relative to frontend/ (no "frontend/" prefix)', () => {
    const r = frontend({ changedFiles: [`${MAIN}/leave/LeaveController.java`] });
    for (const f of r.extraTestFiles) {
      assert.ok(!f.startsWith('frontend/'), `expected frontend-relative path, got ${f}`);
    }
  });

  it('is deterministic regardless of input order', () => {
    const changedFiles = ['frontend/src/a.js', 'docs/api/api-surface.json', 'frontend/src/b.jsx'];
    const a = frontend({ changedFiles, testFilesReadingFs: [...FS_READING_TESTS] });
    const b = frontend({ changedFiles: [...changedFiles].reverse(), testFilesReadingFs: [...FS_READING_TESTS].reverse() });
    assert.deepEqual(a, b);
  });
});

describe('selectFrontendTests — nothing to run', () => {
  it('empty diff → none', () => {
    const r = frontend({ changedFiles: [] });
    assert.equal(r.mode, 'none');
    assert.deepEqual(r.extraTestFiles, []);
  });

  it('F9 wrong-way-round: a backend Java file no fs-reading test mentions → none', () => {
    const r = frontend({ changedFiles: [`${MAIN}/leave/LeaveService.java`, 'backend/pom.xml'] });
    assert.equal(r.mode, 'none');
    assert.deepEqual(r.extraTestFiles, []);
  });

  it('a backend *ControllerTest.java (test source) does not count as a controller change', () => {
    const r = frontend({ changedFiles: [`${TEST}/leave/LeaveControllerTest.java`] });
    assert.equal(r.mode, 'none');
  });

  it('docs outside docs/api → none', () => {
    const r = frontend({ changedFiles: ['README.md', 'docs/other/notes.md', 'CLAUDE.md'] });
    assert.equal(r.mode, 'none');
  });

  it('backend-ci workflow change alone → none for the frontend', () => {
    const r = frontend({ changedFiles: ['.github/workflows/backend-ci.yml'] });
    assert.equal(r.mode, 'none');
  });
});
