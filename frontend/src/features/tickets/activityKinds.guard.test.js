import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ACTIVITY_KINDS, activityKindIcon, activityKindLabel, activityNoteRequired } from './dealTrackingMeta.js';
import { timelineRowMatches } from './dealTimeline.js';

// Slice 6d (ส่งตัวอย่าง = SAMPLE_SENT, V225). The deal-activity kinds live in three places that must agree — the
// database constraint, DealActivityKind.java and dealTrackingMeta.js's ACTIVITY_KINDS (which the mock validates
// through) — and in every other file that spells the whole list out. This guard reads the repository itself, so a
// kind added in one place and forgotten in another fails here rather than as a 400 or a raw code on screen.
// (The database side is also read back from a live Postgres by DealActivitySampleSentIntegrationTest.)

function repoRoot() {
  for (let dir = process.cwd(), depth = 0; depth < 8; depth += 1, dir = dirname(dir)) {
    if (existsSync(join(dir, 'backend', 'src', 'main')) && existsSync(join(dir, 'frontend', 'src'))) return dir;
  }
  throw new Error(`could not find the repository root above ${process.cwd()}`);
}
const ROOT = repoRoot();
const read = (path) => readFileSync(join(ROOT, path), 'utf8');

const ORIGINAL_KINDS = ['CALL', 'MEETING', 'SITE_VISIT', 'EMAIL', 'MESSAGE', 'QUOTATION_FOLLOWUP', 'OTHER'];
const sorted = (list) => [...list].sort();

function javaKinds() {
  const source = read('backend/src/main/java/th/co/glr/hr/ticket/DealActivityKind.java');
  const constants = Object.fromEntries([...source.matchAll(/String\s+([A-Z_]+)\s*=\s*"([A-Z_]+)"/g)].map((m) => [m[1], m[2]]));
  const valid = /VALID\s*=\s*Set\.of\(([^)]*)\)/s.exec(source)[1];
  return valid.split(',').map((name) => constants[name.trim()]);
}

/** The kinds in the LAST migration that declares chk_deal_activity_kind — what a fresh database ends up with. Files
 * are filtered by content before the highest version is taken, so a later migration that does not touch this constraint
 * (e.g. V223/V224 from other PRs, below or above ours) never hides it. */
function constraintKinds() {
  const dir = 'backend/src/main/resources/db/migration';
  const version = (file) => file.slice(1, file.indexOf('__')).split('.').map(Number);
  const byVersion = (a, b) => {
    const [x, y] = [version(a), version(b)];
    for (let i = 0; i < Math.max(x.length, y.length); i += 1) {
      if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) - (y[i] ?? 0);
    }
    return 0;
  };
  const declaring = readdirSync(join(ROOT, dir))
    .filter((file) => /^V[\d.]+__.*\.sql$/.test(file))
    .sort(byVersion)
    .filter((file) => /ADD CONSTRAINT chk_deal_activity_kind|CONSTRAINT chk_deal_activity_kind CHECK/.test(read(`${dir}/${file}`)));
  const sql = read(`${dir}/${declaring.at(-1)}`);
  const check = /chk_deal_activity_kind CHECK \(kind IN \(([^)]*)\)/s.exec(sql)[1];
  return [...check.matchAll(/'([A-Z_]+)'/g)].map((m) => m[1]);
}

describe('deal-activity kinds: one list everywhere', () => {
  it('dealTrackingMeta, DealActivityKind.java and the latest chk_deal_activity_kind name exactly the same kinds', () => {
    const frontend = sorted(ACTIVITY_KINDS.map((k) => k.code));
    expect(frontend).toContain('SAMPLE_SENT');
    expect(frontend).toEqual(sorted(javaKinds()));
    expect(frontend).toEqual(sorted(constraintKinds()));
  });

  it('ส่งตัวอย่าง has its own label and icon, and is the one kind whose note (what was sent) is required', () => {
    expect(activityKindLabel('SAMPLE_SENT')).toBe('ส่งตัวอย่าง');
    expect(activityKindIcon('SAMPLE_SENT')).toBe('package');
    expect(ACTIVITY_KINDS.filter((k) => activityNoteRequired(k.code)).map((k) => k.code)).toEqual(['SAMPLE_SENT']);
    // Every kind has a label of its own (never the raw code) and an icon.
    ACTIVITY_KINDS.forEach((k) => {
      expect(activityKindLabel(k.code)).not.toBe(k.code);
      expect(activityKindIcon(k.code)).toBeTruthy();
    });
  });

  it('ส่งตัวอย่าง is not a visit: the timeline\'s เข้าพบ filter leaves it out, ทั้งหมด keeps it', () => {
    const row = { type: 'activity', activity: { kind: 'SAMPLE_SENT' } };
    expect(timelineRowMatches(row, 'visits')).toBe(false);
    expect(timelineRowMatches(row, 'all')).toBe(true);
    expect(timelineRowMatches(row, 'documents')).toBe(false);
  });

  // A file that spells out three or more of the kinds is listing them; it must list the new one too. Applied
  // migrations are history and are never edited (CLAUDE.md), so V83 is the one exemption, with its reason.
  it('every file that lists the kinds names SAMPLE_SENT too (grep guard over backend, frontend, e2e-real, docs)', () => {
    const EXEMPT = {
      'backend/src/main/resources/db/migration/V83__deal_tracking_and_activity.sql':
        'applied migration — V225 re-declares the constraint; an applied migration is never edited',
    };
    const roots = ['backend/src/main', 'frontend/src', 'frontend/e2e-real', 'docs/repo-map'];
    const files = [];
    const walk = (dir) => readdirSync(join(ROOT, dir)).forEach((name) => {
      const path = `${dir}/${name}`;
      if (name === 'node_modules') return;
      if (statSync(join(ROOT, path)).isDirectory()) walk(path);
      else if (/\.(java|sql|js|jsx|md)$/.test(name) && !/\.test\.(js|jsx)$/.test(name)) files.push(path);
    });
    roots.forEach(walk);

    const listing = files.filter((path) => {
      const text = read(path);
      return ORIGINAL_KINDS.filter((kind) => new RegExp(`['"\`]${kind}['"\`]`).test(text)).length >= 3;
    });
    expect(listing.length, 'the guard found the files it is meant to watch').toBeGreaterThanOrEqual(4);
    const missing = listing.filter((path) => !EXEMPT[path] && !read(path).includes('SAMPLE_SENT'));
    expect(missing, `lists the activity kinds without SAMPLE_SENT: ${missing.join(', ')}`).toEqual([]);
    expect(Object.keys(EXEMPT).every((path) => listing.includes(path)), 'an exemption that no longer lists kinds')
      .toBe(true);
  });
});
