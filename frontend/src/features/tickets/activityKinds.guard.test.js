import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ACTIVITY_KINDS } from './dealTrackingMeta.js';

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

/** The text without comments: `/* … *\/`, `// …` (not the `//` of a URL) and SQL `-- …`. A kind named only in a
 * comment neither makes a list nor satisfies one. */
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:\\])\/\/.*$/gm, '$1')
    .replace(/(^|\s)--.*$/gm, '$1');
}

/** Every shape that names a kind: a quoted code, a bare object key (`CALL: …`), a qualified constant
 * (`DealActivityKind.CALL`, or through any other object — `KINDS.CALL`, round 2), or a switch label (`case CALL ->`,
 * `case CALL:`). */
function kindForms(kind) {
  return [
    new RegExp(`['"\`]${kind}['"\`]`),
    new RegExp(`(^|[{,\\s])${kind}\\s*:(?!:)`, 'm'),
    new RegExp(`\\b[A-Za-z_$][\\w$]*\\.${kind}\\b`),
    new RegExp(`\\bcase\\s+${kind}\\s*(->|:)`),
  ];
}

/** A file lists the kinds when, outside comments, it names three or more of them in any of those shapes. */
function listsKinds(text) {
  const code = stripComments(text);
  const enums = enumBodies(code);
  return ORIGINAL_KINDS.filter((kind) => kindForms(kind).some((form) => form.test(code))
    || new RegExp(`\\b${kind}\\b`).test(enums)).length >= 3;
}

/** Round 2: the constants of every Java enum body (`enum X { CALL, MEETING, … ` up to the first `;` or `}`) — a kind
 * named there counts too. */
function enumBodies(code) {
  return [...code.matchAll(/\benum\s+\w+[^{]*\{([^;}]*)/g)].map((m) => m[1]).join(' ');
}

/** …and it lists SAMPLE_SENT when the code itself names it — a comment does not count. */
function namesSampleSent(text) {
  return /\bSAMPLE_SENT\b/.test(stripComments(text));
}

function javaKinds() {
  const source = read('backend/src/main/java/th/co/glr/hr/ticket/DealActivityKind.java');
  const constants = Object.fromEntries([...source.matchAll(/String\s+([A-Z_]+)\s*=\s*"([A-Z_]+)"/g)].map((m) => [m[1], m[2]]));
  const valid = /VALID\s*=\s*Set\.of\(([^)]*)\)/s.exec(source)[1];
  return valid.split(',').map((name) => constants[name.trim()]);
}

/** The kinds in the LAST migration that declares chk_deal_activity_kind — what a fresh database ends up with. Files
 * are filtered by content before the highest version is taken, so a later migration that does not touch this constraint
 * (e.g. another PR's V223 or V225, below or above ours) never hides it. */
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

  // Fix round 1 (review M1): the scan below must see the shapes a kind list actually takes, not only quoted codes.
  // The two escapes the review demonstrated, and SAMPLE_SENT named only in a comment.
  describe('what the scan counts as "lists the kinds"', () => {
    const DEMO_LABEL_MAP = `export const KIND_LABELS = { CALL: 'โทร', MEETING: 'ประชุม', SITE_VISIT: 'เข้าหน้างาน', EMAIL: 'อีเมล', OTHER: 'อื่น ๆ' };`;
    const DEMO_JAVA_SWITCH = `return switch (kind) {
            case DealActivityKind.CALL -> "call";
            case DealActivityKind.MEETING -> "meeting";
            case DealActivityKind.SITE_VISIT -> "visit";
            default -> "?";
        };`;
    const DEMO_BARE_CASES = `switch (kind) { case CALL -> 1; case MEETING -> 2; case EMAIL -> 3; default -> 0; }`;
    const DEMO_OTHER_QUALIFIER = `const order = [KINDS.CALL, KINDS.MEETING, KINDS.EMAIL];`;
    const DEMO_JAVA_ENUM = `public enum ActivityKind {
    CALL,
    MEETING,
    EMAIL;
}`;
    const COMMENT_ONLY = `// SAMPLE_SENT is handled elsewhere\nconst KINDS = ['CALL', 'MEETING', 'SITE_VISIT'];\n/* SAMPLE_SENT */`;

    it.each([
      ['a label map keyed by bare identifiers', DEMO_LABEL_MAP],
      ['a Java switch over DealActivityKind constants', DEMO_JAVA_SWITCH],
      ['a switch over bare enum constants', DEMO_BARE_CASES],
      ['a quoted list whose SAMPLE_SENT is only in comments', COMMENT_ONLY],
      // Round 2: kinds qualified through some other object, and a Java enum body.
      ['a list qualified through another object', DEMO_OTHER_QUALIFIER],
      ['a Java enum body', DEMO_JAVA_ENUM],
    ])('%s is a kind list missing SAMPLE_SENT', (_shape, text) => {
      expect(listsKinds(text)).toBe(true);
      expect(namesSampleSent(text)).toBe(false);
    });

    it('the same shapes naming SAMPLE_SENT in code pass', () => {
      expect(namesSampleSent(`${DEMO_LABEL_MAP.replace('};', ", SAMPLE_SENT: 'ส่งตัวอย่าง' };")}`)).toBe(true);
      expect(namesSampleSent(DEMO_JAVA_SWITCH.replace('default', 'case DealActivityKind.SAMPLE_SENT -> "s";\n default'))).toBe(true);
    });

    it('two kinds, or one kind and unrelated words, is not a list', () => {
      expect(listsKinds(`const VISIT_ACTIVITY_KINDS = new Set(['SITE_VISIT', 'MEETING']);`)).toBe(false);
      expect(listsKinds(`fail('OTHER'); const x = { EMAIL: 1 };`)).toBe(false);
      expect(listsKinds(`const VISITS = new Set([KINDS.SITE_VISIT, KINDS.MEETING]);`)).toBe(false);
      expect(listsKinds(`enum Visit { SITE_VISIT, MEETING }`)).toBe(false);
    });
  });

  // A file that spells out three or more of the kinds is listing them; it must list the new one too. Applied
  // migrations are history and are never edited (CLAUDE.md), so V83 is the one exemption, with its reason.
  it('every file that lists the kinds names SAMPLE_SENT too (grep guard over backend, frontend, e2e-real, docs)', () => {
    const EXEMPT = {
      'backend/src/main/resources/db/migration/V83__deal_tracking_and_activity.sql':
        'applied migration — V224 re-declares the constraint; an applied migration is never edited',
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

    const listing = files.filter((path) => listsKinds(read(path)));
    expect(listing.length, 'the guard found the files it is meant to watch').toBeGreaterThanOrEqual(4);
    const missing = listing.filter((path) => !EXEMPT[path] && !namesSampleSent(read(path)));
    expect(missing, `lists the activity kinds without SAMPLE_SENT: ${missing.join(', ')}`).toEqual([]);
    expect(Object.keys(EXEMPT).every((path) => listing.includes(path)), 'an exemption that no longer lists kinds')
      .toBe(true);
  });
});
