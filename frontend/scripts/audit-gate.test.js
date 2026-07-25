// Tests for the CI dependency-audit gate.
//
// The point of these is adversarial: an allowlist-based gate is only worth having if
// it still goes RED for anything not explicitly reviewed. Most of the cases below are
// written wrong-way-round -- they assert the gate FAILS -- because a gate that cannot
// fail is not a gate. See CLAUDE.md, "Mutation-check the guard".
//
// The script is driven as a real subprocess against fixture reports (AUDIT_REPORT_FILE)
// so exit codes, not just return values, are what gets asserted.

import { describe, expect, it, beforeAll, afterAll } from 'vitest';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT = resolve(dirname(fileURLToPath(import.meta.url)), 'audit-gate.mjs');

let workDir;
beforeAll(() => {
  workDir = mkdtempSync(join(tmpdir(), 'audit-gate-'));
});
afterAll(() => {
  rmSync(workDir, { recursive: true, force: true });
});

let seq = 0;
function runGate({ report, allowlist, today = '2026-07-25', level }) {
  const id = seq++;
  const reportPath = join(workDir, `report-${id}.json`);
  const allowlistPath = join(workDir, `allowlist-${id}.json`);
  writeFileSync(reportPath, typeof report === 'string' ? report : JSON.stringify(report));
  writeFileSync(allowlistPath, typeof allowlist === 'string' ? allowlist : JSON.stringify(allowlist));

  const res = spawnSync(process.execPath, [SCRIPT], {
    encoding: 'utf8',
    env: {
      ...process.env,
      AUDIT_REPORT_FILE: reportPath,
      AUDIT_ALLOWLIST_FILE: allowlistPath,
      AUDIT_TODAY: today,
      ...(level ? { AUDIT_LEVEL: level } : {}),
    },
  });
  const out = `${res.stdout}${res.stderr}`;

  // An unhandled exception also exits 1, so exit code alone cannot tell a deliberate
  // gate failure from a crash. This bit us for real: an earlier draft of the script
  // threw a TypeError under a mutation, and every "must fail" test passed on the
  // crash while the guard itself was defeated. `rejected` is what the assertions use.
  const crashed = /\b(TypeError|ReferenceError|SyntaxError|RangeError)\b/.test(out);
  const rejected = res.status === 1 && !crashed && /^FAIL[:!]?/m.test(out);
  return { code: res.status, out, crashed, rejected };
}

/** Asserts the gate deliberately rejected -- not that it merely exited non-zero. */
function expectRejected({ code, out, crashed, rejected }) {
  expect(crashed, `gate crashed instead of failing cleanly:\n${out}`).toBe(false);
  expect(out).not.toContain('PASS:');
  expect(rejected, `gate did not emit a FAIL verdict:\n${out}`).toBe(true);
  expect(code).toBe(1);
}

/** Mirrors the shape npm emits: a chain of packages ending in advisory objects. */
function report(vulnerabilities, counts) {
  const severities = { info: 0, low: 0, moderate: 0, high: 0, critical: 0, ...counts };
  return { vulnerabilities, metadata: { vulnerabilities: severities } };
}

const advisory = (ghsa) => ({ url: `https://github.com/advisories/${ghsa}`, title: ghsa });

// The real 2026-07-25 situation: 9 records resolving to 2 root advisories.
const REAL_REPORT = report(
  {
    'brace-expansion': { severity: 'high', via: [advisory('GHSA-mh99-v99m-4gvg')] },
    minimatch: { severity: 'high', via: ['brace-expansion'] },
    '@eslint/config-array': { severity: 'high', via: ['minimatch'] },
    '@eslint/eslintrc': { severity: 'high', via: ['minimatch'] },
    eslint: { severity: 'high', via: ['@eslint/config-array', '@eslint/eslintrc', 'minimatch'] },
    'eslint-plugin-jsx-a11y': { severity: 'high', via: ['minimatch'] },
    'eslint-plugin-react': { severity: 'high', via: ['minimatch'] },
    'react-router': { severity: 'high', via: [advisory('GHSA-qwww-vcr4-c8h2')] },
    'react-router-dom': { severity: 'high', via: ['react-router'] },
  },
  { high: 9 },
);

const entry = (over = {}) => ({
  id: 'GHSA-mh99-v99m-4gvg',
  package: 'brace-expansion',
  scope: 'dev',
  reason: 'dev-only lint toolchain, no untrusted input',
  reviewBy: '2026-10-23',
  ...over,
});

const REAL_ALLOWLIST = {
  allow: [
    entry(),
    entry({ id: 'GHSA-qwww-vcr4-c8h2', package: 'react-router', scope: 'prod', reason: 'RSC-only; app is a client SPA' }),
  ],
};

describe('audit-gate — passing cases', () => {
  it('passes when every advisory resolves to a reviewed root entry', () => {
    const { code, out } = runGate({ report: REAL_REPORT, allowlist: REAL_ALLOWLIST });
    expect(out).toContain('PASS');
    expect(code).toBe(0);
  });

  it('clears a whole transitive chain from a single root entry', () => {
    // 7 of the 9 records are only vulnerable via brace-expansion; one entry covers them.
    const { out } = runGate({ report: REAL_REPORT, allowlist: REAL_ALLOWLIST });
    expect(out).toContain('9 record(s) cleared');
    expect(out).toContain('eslint-plugin-jsx-a11y (high) via GHSA-mh99-v99m-4gvg');
  });

  it('passes on a clean report', () => {
    const { code, out } = runGate({ report: report({}, {}), allowlist: { allow: [] } });
    expect(out).toContain('PASS');
    expect(code).toBe(0);
  });

  it('ignores advisories below the threshold', () => {
    const r = report({ leftpad: { severity: 'low', via: [advisory('GHSA-low-0000-0000')] } }, { low: 1 });
    const { code } = runGate({ report: r, allowlist: { allow: [] } });
    expect(code).toBe(0);
  });

  it('warns but still passes when an allowlist entry matches nothing (upstream fixed it)', () => {
    const { code, out } = runGate({
      report: report({}, {}),
      allowlist: { allow: [entry()] },
    });
    expect(out).toContain('no longer match any advisory');
    expect(out).toContain('GHSA-mh99-v99m-4gvg');
    expect(code).toBe(0);
  });
});

describe('audit-gate — must fail (mutation checks)', () => {
  it('FAILS when a brand-new unrelated advisory appears', () => {
    // The regression that matters most: the allowlist must not become a blanket waiver.
    const r = report(
      {
        ...REAL_REPORT.vulnerabilities,
        'some-new-pkg': { severity: 'high', via: [advisory('GHSA-newl-yfou-nd01')] },
      },
      { high: 10 },
    );
    const res = runGate({ report: r, allowlist: REAL_ALLOWLIST });
    expect(res.out).toContain('some-new-pkg');
    expect(res.out).toContain('GHSA-newl-yfou-nd01');
    expectRejected(res);
  });

  it('FAILS when the react-router entry is removed', () => {
    const res = runGate({
      report: REAL_REPORT,
      allowlist: { allow: [entry()] },
    });
    expect(res.out).toContain('react-router');
    expectRejected(res);
  });

  it('FAILS when the brace-expansion entry is removed, naming the whole chain', () => {
    const res = runGate({
      report: REAL_REPORT,
      allowlist: { allow: [REAL_ALLOWLIST.allow[1]] },
    });
    expect(res.out).toContain('brace-expansion');
    expect(res.out).toContain('eslint-plugin-react');
    expectRejected(res);
  });

  it('FAILS when an entry is past its reviewBy date', () => {
    const res = runGate({
      report: REAL_REPORT,
      allowlist: REAL_ALLOWLIST,
      today: '2026-10-24', // one day after reviewBy
    });
    expect(res.out).toContain('past their reviewBy date');
    expectRejected(res);
  });

  it('passes on the reviewBy date itself, fails the day after', () => {
    expect(runGate({ report: REAL_REPORT, allowlist: REAL_ALLOWLIST, today: '2026-10-23' }).code).toBe(0);
    expectRejected(runGate({ report: REAL_REPORT, allowlist: REAL_ALLOWLIST, today: '2026-10-24' }));
  });

  it('FAILS a package vulnerable via both a reviewed AND an unreviewed advisory', () => {
    // Partial coverage must not clear a package -- otherwise a reviewed advisory
    // could silently shelter a new one sharing the same dependency.
    const r = report(
      {
        'react-router': {
          severity: 'high',
          via: [advisory('GHSA-qwww-vcr4-c8h2'), advisory('GHSA-unre-view-ed99')],
        },
      },
      { high: 1 },
    );
    const res = runGate({ report: r, allowlist: REAL_ALLOWLIST });
    expect(res.out).toContain('GHSA-unre-view-ed99');
    expect(res.out).not.toContain('PASS');
    expectRejected(res);
  });

  it('FAILS when an allowlist entry omits a required field', () => {
    const noReason = { ...entry() };
    delete noReason.reason;
    const res = runGate({ report: REAL_REPORT, allowlist: { allow: [noReason] } });
    expect(res.out).toContain('missing required fields');
    expectRejected(res);
  });

  it('FAILS on a malformed allowlist rather than defaulting to permissive', () => {
    const res = runGate({ report: REAL_REPORT, allowlist: { nope: [] } });
    expect(res.out).toContain('must contain an "allow" array');
    expectRejected(res);
  });

  it('FAILS on an unparseable audit report rather than passing silently', () => {
    const res = runGate({ report: 'not json at all', allowlist: REAL_ALLOWLIST });
    expect(res.out).toContain('could not parse');
    expectRejected(res);
  });

  it('FAILS when npm audit itself errored', () => {
    const r = { error: { summary: 'registry unreachable' } };
    const res = runGate({ report: r, allowlist: REAL_ALLOWLIST });
    expect(res.out).toContain('registry unreachable');
    expectRejected(res);
  });

  it('FAILS on an unknown AUDIT_LEVEL rather than silently gating nothing', () => {
    const res = runGate({ report: REAL_REPORT, allowlist: REAL_ALLOWLIST, level: 'bogus' });
    expect(res.out).toContain('unknown AUDIT_LEVEL');
    expectRejected(res);
  });
});

// These four were found by attacking the gate after it was already "done" and passing.
// Every one of them originally failed OPEN -- the gate returned 0 on input it should
// have refused. They are the reason the gate treats unknown input as in-scope.
describe('audit-gate — fail-closed on malformed input', () => {
  it('FAILS on a record with no severity field, rather than skipping it as below-threshold', () => {
    const r = report({ pkg: { via: [advisory('GHSA-zzzz-zzzz-zzzz')] } }, { high: 1 });
    expectRejected(runGate({ report: r, allowlist: REAL_ALLOWLIST }));
  });

  it('FAILS on an unrecognised severity label rather than ignoring it', () => {
    // If npm ever introduces a new severity name, it must not silently fall through.
    const r = report({ pkg: { severity: 'catastrophic', via: [advisory('GHSA-zzzz-zzzz-zzzz')] } }, { high: 1 });
    expectRejected(runGate({ report: r, allowlist: REAL_ALLOWLIST }));
  });

  it('FAILS on a non-zero-padded reviewBy that would sort as far-future', () => {
    // "2026-9-01" > "2026-10-23" under string comparison, so a typo would have granted
    // a permanent waiver. This is the highest-consequence of the four.
    const bad = { allow: [entry({ reviewBy: '2026-9-01' })] };
    const res = runGate({ report: REAL_REPORT, allowlist: bad });
    expect(res.out).toContain('zero-padded ISO date');
    expectRejected(res);
  });

  it('FAILS on a calendar-impossible reviewBy such as 2026-02-30', () => {
    expectRejected(runGate({ report: report({}, {}), allowlist: { allow: [entry({ reviewBy: '2026-02-30' })] } }));
  });

  it('still ignores a genuine below-threshold advisory (no false positives)', () => {
    const r = report({ pkg: { severity: 'low', via: [advisory('GHSA-zzzz-zzzz-zzzz')] } }, { low: 1 });
    expect(runGate({ report: r, allowlist: REAL_ALLOWLIST }).code).toBe(0);
  });
});

describe('audit-gate — via-graph resolution', () => {
  it('does not hang on a cyclic via graph', () => {
    const r = report(
      {
        a: { severity: 'high', via: ['b'] },
        b: { severity: 'high', via: ['a'] },
      },
      { high: 2 },
    );
    const res = runGate({ report: r, allowlist: REAL_ALLOWLIST });
    // No advisory id resolves, so nothing is cleared -- it must fail, not pass.
    expectRejected(res);
    expect(res.out).toContain('no advisory id resolved');
  });

  it('falls back to the numeric npm advisory id when no url is present', () => {
    const r = report({ pkg: { severity: 'high', via: [{ source: 1124334, title: 'x' }] } }, { high: 1 });
    expect(runGate({ report: r, allowlist: { allow: [entry({ id: '1124334' })] } }).code).toBe(0);
    expectRejected(runGate({ report: r, allowlist: REAL_ALLOWLIST }));
  });
});
