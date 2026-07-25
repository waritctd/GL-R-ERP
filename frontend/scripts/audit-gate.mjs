#!/usr/bin/env node
// CI dependency-audit gate.
//
// Replaces a bare `npm audit --audit-level=moderate`. Same threshold, same scope
// (prod AND dev) -- the only difference is that advisories explicitly reviewed and
// recorded in audit-allowlist.json are subtracted, and each of those carries a hard
// reviewBy deadline.
//
// Fails (exit 1) when:
//   - any advisory at or above the threshold is NOT allowlisted
//   - any allowlist entry is past its reviewBy date
//   - the allowlist is malformed, or `npm audit` could not be run
//
// Warns (exit 0) when an allowlist entry no longer matches anything -- that means
// upstream shipped a fix, which is good news; the entry just wants deleting.
//
// Why an allowlist and not `--audit-level=critical` or `--omit=dev`: raising the
// threshold would stop gating every future high-severity production advisory, and
// --omit=dev would permanently stop gating build-tooling (it is what caught the
// postcss path-traversal fixed on this branch). Both trade a broad blind spot for a
// narrow problem. See docs/agent-handoffs/114_fix-frontend-audit-advisories.md.

import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const FRONTEND_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// Both overridable so the gate's own behaviour can be exercised against fixture
// reports (scripts/audit-gate.test.js) rather than whatever the registry happens to
// be serving today. Unset in CI, which audits the real tree.
const ALLOWLIST_PATH = process.env.AUDIT_ALLOWLIST_FILE
  ? resolve(process.env.AUDIT_ALLOWLIST_FILE)
  : resolve(FRONTEND_DIR, 'audit-allowlist.json');
const REPORT_FILE = process.env.AUDIT_REPORT_FILE
  ? resolve(process.env.AUDIT_REPORT_FILE)
  : null;

const SEVERITY_ORDER = ['info', 'low', 'moderate', 'high', 'critical'];
const THRESHOLD = process.env.AUDIT_LEVEL || 'moderate';

const thresholdRank = SEVERITY_ORDER.indexOf(THRESHOLD);
if (thresholdRank === -1) {
  console.error(`FAIL: audit-gate — unknown AUDIT_LEVEL "${THRESHOLD}" (expected one of ${SEVERITY_ORDER.join(', ')})`);
  process.exit(1);
}

/** Today in UTC as YYYY-MM-DD, so reviewBy comparison is timezone-independent. */
const today = process.env.AUDIT_TODAY || new Date().toISOString().slice(0, 10);

// ---------------------------------------------------------------- allowlist

let allowlist;
try {
  allowlist = JSON.parse(readFileSync(ALLOWLIST_PATH, 'utf8'));
} catch (err) {
  console.error(`FAIL: audit-gate — cannot read ${ALLOWLIST_PATH}: ${err.message}`);
  process.exit(1);
}

if (!Array.isArray(allowlist?.allow)) {
  console.error('FAIL: audit-gate — audit-allowlist.json must contain an "allow" array');
  process.exit(1);
}

const REQUIRED_FIELDS = ['id', 'package', 'reason', 'reviewBy'];
const malformed = allowlist.allow.filter((e) => REQUIRED_FIELDS.some((f) => !e?.[f]));
if (malformed.length > 0) {
  console.error(`FAIL: audit-gate — allowlist entries missing required fields (${REQUIRED_FIELDS.join(', ')}):`);
  for (const entry of malformed) console.error(`  - ${JSON.stringify(entry?.id ?? entry)}`);
  process.exit(1);
}

// reviewBy is compared as a string, which is only sound for zero-padded ISO dates.
// "2026-9-01" would sort AFTER "2026-10-23" and so never expire -- a typo would silently
// grant a permanent waiver. Reject anything that is not a real, zero-padded YYYY-MM-DD.
const badDates = allowlist.allow.filter((e) => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(e.reviewBy)) return true;
  const parsed = new Date(`${e.reviewBy}T00:00:00Z`);
  return Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== e.reviewBy;
});
if (badDates.length > 0) {
  console.error('FAIL: audit-gate — reviewBy must be a real zero-padded ISO date (YYYY-MM-DD):');
  for (const entry of badDates) console.error(`  - ${entry.id}: "${entry.reviewBy}"`);
  process.exit(1);
}

const allowedById = new Map(allowlist.allow.map((e) => [e.id, e]));
const expired = allowlist.allow.filter((e) => e.reviewBy < today);

// ---------------------------------------------------------------- npm audit

let rawReport;
if (REPORT_FILE) {
  try {
    rawReport = readFileSync(REPORT_FILE, 'utf8');
  } catch (err) {
    console.error(`FAIL: audit-gate — cannot read AUDIT_REPORT_FILE ${REPORT_FILE}: ${err.message}`);
    process.exit(1);
  }
} else {
  // `npm audit` exits non-zero whenever it finds anything, so the exit code is not a
  // failure signal here -- only unparseable stdout is.
  const audit = spawnSync('npm', ['audit', '--json'], {
    cwd: FRONTEND_DIR,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });

  if (audit.error) {
    console.error(`FAIL: audit-gate — failed to run npm audit: ${audit.error.message}`);
    process.exit(1);
  }
  rawReport = audit.stdout;
}

let report;
try {
  report = JSON.parse(rawReport);
} catch {
  console.error('FAIL: audit-gate — could not parse `npm audit --json` output.');
  console.error(rawReport?.slice(0, 2000) || '(empty stdout)');
  process.exit(1);
}

if (report.error) {
  console.error(`FAIL: audit-gate — npm audit reported an error: ${report.error.summary || JSON.stringify(report.error)}`);
  process.exit(1);
}

const vulnerabilities = report.vulnerabilities || {};

// ------------------------------------------------- resolve root advisory ids
//
// npm reports a chain: `react-router-dom` is vulnerable "via" `react-router`,
// which is vulnerable "via" an advisory object. Allowlisting has to key on the
// root advisory so one reviewed entry clears its whole dependency chain rather
// than needing a line per affected package.

/** @returns {Set<string>} GHSA ids that make `name` vulnerable. */
function rootAdvisories(name, seen = new Set()) {
  if (seen.has(name)) return new Set(); // defensive: `via` graphs can cycle
  seen.add(name);

  const ids = new Set();
  for (const via of vulnerabilities[name]?.via ?? []) {
    if (typeof via === 'string') {
      for (const id of rootAdvisories(via, seen)) ids.add(id);
    } else if (via?.url) {
      ids.add(via.url.split('/').pop());
    } else if (via?.source != null) {
      ids.add(String(via.source));
    }
  }
  return ids;
}

const blocking = [];
const suppressed = [];
const matchedAllowIds = new Set();

for (const [name, vuln] of Object.entries(vulnerabilities)) {
  // An absent or unrecognised severity must NOT be read as "below threshold" -- that
  // would let a record npm labels in some new way slip through unexamined. Unknown
  // severity is treated as in-scope and therefore requires review.
  const rank = SEVERITY_ORDER.indexOf(vuln.severity);
  if (rank !== -1 && rank < thresholdRank) continue;

  const ids = [...rootAdvisories(name)];
  // Only cleared when EVERY root cause has been reviewed. A package vulnerable
  // through both a known and an unknown advisory must still fail.
  const unreviewed = ids.filter((id) => !allowedById.has(id));

  if (ids.length > 0 && unreviewed.length === 0) {
    ids.forEach((id) => matchedAllowIds.add(id));
    suppressed.push({ name, severity: vuln.severity || 'unknown', ids });
  } else {
    blocking.push({ name, severity: vuln.severity || 'unknown', ids, unreviewed });
  }
}

// ---------------------------------------------------------------- reporting

const counts = report.metadata?.vulnerabilities ?? {};
const total = Object.entries(counts)
  .filter(([sev]) => SEVERITY_ORDER.indexOf(sev) >= thresholdRank)
  .reduce((sum, [, n]) => sum + n, 0);

console.log(`audit-gate: threshold=${THRESHOLD}, scope=all dependencies (prod + dev)`);
console.log(`audit-gate: npm audit reports ${total} advisory record(s) at or above ${THRESHOLD}.\n`);

if (suppressed.length > 0) {
  console.log(`Reviewed exceptions (${suppressed.length} record(s) cleared by audit-allowlist.json):`);
  for (const { name, severity, ids } of suppressed) {
    console.log(`  - ${name} (${severity}) via ${ids.join(', ')}`);
  }
  console.log('');
  for (const id of matchedAllowIds) {
    const entry = allowedById.get(id);
    if (!entry) continue; // unreachable while the clearing rule holds; never crash the gate over formatting
    console.log(`  ${id} [${entry.package}, ${entry.scope || 'unknown'} scope] review by ${entry.reviewBy}`);
  }
  console.log('');
}

const unusedEntries = allowlist.allow.filter((e) => !matchedAllowIds.has(e.id));
if (unusedEntries.length > 0) {
  console.log('NOTE: these allowlist entries no longer match any advisory — upstream likely');
  console.log('shipped a fix. Please delete them from audit-allowlist.json:');
  for (const entry of unusedEntries) console.log(`  - ${entry.id} (${entry.package})`);
  console.log('');
}

let failed = false;

if (expired.length > 0) {
  failed = true;
  console.error(`FAIL: ${expired.length} allowlist entry/entries are past their reviewBy date (today is ${today}).`);
  console.error('An exception is a deferral, not a permanent waiver. Re-check whether a fix now');
  console.error('exists; if it still does not, extend reviewBy deliberately and say why.');
  for (const entry of expired) {
    console.error(`  - ${entry.id} (${entry.package}) expired ${entry.reviewBy} — ${entry.clearWhen || 'no clearWhen recorded'}`);
  }
  console.error('');
}

if (blocking.length > 0) {
  failed = true;
  console.error(`FAIL: ${blocking.length} advisory record(s) at or above ${THRESHOLD} are not reviewed.`);
  console.error('Fix them if a fix exists (`npm audit fix`). Only if none does, add the ROOT');
  console.error('advisory id to audit-allowlist.json with a reachability argument and a reviewBy date.\n');
  for (const { name, severity, unreviewed } of blocking) {
    console.error(`  - ${name} (${severity}) — unreviewed: ${unreviewed.join(', ') || '(no advisory id resolved)'}`);
  }
  console.error('\nRun `npm audit` in frontend/ for the full report.');
}

if (failed) process.exit(1);

console.log('PASS: no unreviewed advisories at or above the threshold, and no expired exceptions.');
