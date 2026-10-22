// Selective test picker for PR CI.
//
// On a pull_request the workflows run only the tests plausibly affected by the diff; on a
// push to develop/main they run everything (the safety net). The decision logic lives in the
// two pure functions below — no fs, no child_process — so `node --test` can pin it with
// fixtures (select-tests.test.mjs). The CLI at the bottom is the only thing that touches git
// and the filesystem, and it fails SAFE: any exception prints `mode=full`.
//
// Design rules worth knowing before editing:
//   * Unknown backend paths escalate to FULL. Selection is an allow-list of shapes we
//     understand; anything else is treated as "could affect anything".
//   * A PR whose base branch is `main` is always FULL on both sides (owner ruling
//     2026-10-01): merging to main deploys the frontend, so a PR into main earns no shortcut.
//   * Reverse dependencies are followed ONE level, by matching the fully qualified package
//     prefix `th.co.glr.hr.<pkg>.` (explicit and wildcard imports, FQN references) AND the
//     slash form `th/co/glr/hr/<pkg>` followed by `/`, a quote, whitespace or end-of-text —
//     tests such as attendance/daily/LatenessNeverAffectsPayrollTest read other packages'
//     SOURCE FILES by path with no import at all. The terminator matters: `pricing` must not
//     match `pricingrequest`. Same-package use needs no import and is covered because the
//     changed package itself is always selected.
//   * `-Dtest=` REPLACES surefire's default includes, so the pattern restates them per
//     package. Keep SUREFIRE_INCLUDES in step with maven-surefire's defaults.
//   * Frontend: `vitest --changed` follows the import graph only. Tests that read source or
//     CSS through fs are invisible to it, so they are always appended, and any non-JS change
//     under frontend/ (CSS, HTML, assets) goes FULL. Some of them read BACKEND Java files
//     (stageCatalog.test.js parses DealStage.java), so a changed backend/src/main/java file
//     also pulls in every fs-reading test whose content mentions that file's path or basename.
//   * serverContract.test.js is always added whenever frontend code changed (it scans every
//     screen via apiSurface.js), and any docs/api/** change is a FULL trigger (status-catalog
//     and api-surface are read through helpers no heuristic can attribute to a test).

const HR_MAIN = 'backend/src/main/java/th/co/glr/hr/';
const HR_TEST = 'backend/src/test/java/th/co/glr/hr/';
const SUREFIRE_INCLUDES = ['Test*.java', '*Test.java', '*Tests.java', '*TestCase.java'];
const FLYWAY_TEST = 'th/co/glr/hr/FlywayMigrationTest.java';
const ALWAYS_ON_PACKAGES = ['config', 'support'];
const SERVER_CONTRACT_TEST = 'src/api/serverContract.test.js';
const FULL_CI_LABEL = 'full-ci';
const DEPLOY_BRANCH = 'main';

// ── helpers ─────────────────────────────────────────────────────────────────────────────

function uniqSorted(items) {
  return [...new Set(items)].sort();
}

function universalReason({ isPullRequest, labels, baseRef }) {
  if (!isPullRequest) return 'not a pull request (push) — full suite is the safety net';
  if ((labels ?? []).includes(FULL_CI_LABEL)) return `label "${FULL_CI_LABEL}" present`;
  if (baseRef === DEPLOY_BRANCH) return `pull request targets ${DEPLOY_BRANCH} (merging deploys) — full suite`;
  return null;
}

function basename(path) {
  return path.slice(path.lastIndexOf('/') + 1);
}

// ── backend ─────────────────────────────────────────────────────────────────────────────

function isBackendIgnorable(path) {
  if (path === 'backend/Dockerfile') return true;
  if (!path.startsWith('backend/') || !path.endsWith('.md')) return false;
  // A .md under src/{main,test}/resources is on the classpath (F7): not ignorable.
  return !path.startsWith('backend/src/main/resources/') && !path.startsWith('backend/src/test/resources/');
}

function isBackendFullTrigger(path) {
  if (path === '.github/workflows/backend-ci.yml') return true;
  if (path.startsWith('.github/scripts/')) return true;
  if (!path.startsWith('backend/')) return false;
  if (path === 'backend/pom.xml') return true;
  if (path.startsWith('backend/mvnw')) return true;
  if (path.startsWith('backend/.mvn/')) return true;
  if (path.startsWith('backend/src/main/resources/')) return true;
  if (path.startsWith('backend/src/test/resources/')) return true;
  if (path.startsWith(`${HR_TEST}support/`)) return true;
  if (path.startsWith(`${HR_MAIN}config/`) || path.startsWith(`${HR_MAIN}common/`)) return true;
  // Owner ruling 2026-10-01: test-side common/ is shared test infrastructure, treat like main.
  if (path.startsWith(`${HR_TEST}common/`)) return true;
  return false;
}

/** `backend/src/{main,test}/java/th/co/glr/hr/<pkg>/…/X.java` → `<pkg>`; else null. */
function backendPackageOf(path) {
  for (const root of [HR_MAIN, HR_TEST]) {
    if (!path.startsWith(root) || !path.endsWith('.java')) continue;
    const rest = path.slice(root.length);
    const slash = rest.indexOf('/');
    if (slash === -1) return null; // top-level th/co/glr/hr/X.java → not a package change
    return rest.slice(0, slash);
  }
  return null;
}

function surefirePattern(packages) {
  const perPackage = packages.flatMap((pkg) =>
    SUREFIRE_INCLUDES.map((inc) => `th/co/glr/hr/${pkg}/**/${inc}`));
  return [...perPackage, FLYWAY_TEST].join(',');
}

function escapeRegex(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** True when `content` references package `pkg` in dotted or slash form (see header). */
function referencesPackage(content, pkg) {
  if (content.includes(`th.co.glr.hr.${pkg}.`)) return true;
  return new RegExp(`th/co/glr/hr/${escapeRegex(pkg)}(?=[/"'\\s]|$)`).test(content);
}

export function selectBackendTests({ changedFiles, javaSources, isPullRequest, labels, baseRef }) {
  const full = (reason) => ({ mode: 'full', reason, packages: [], testPattern: '' });
  const universal = universalReason({ isPullRequest, labels, baseRef });
  if (universal) return full(universal);

  const relevant = changedFiles.filter((p) => !isBackendIgnorable(p));
  const trigger = relevant.find(isBackendFullTrigger);
  if (trigger) return full(`full-suite trigger changed: ${trigger}`);

  const changedPackages = new Set();
  for (const path of relevant) {
    if (!path.startsWith('backend/')) continue;
    const pkg = backendPackageOf(path);
    // Fail-safe: a backend path we cannot classify could affect anything.
    if (pkg === null) return full(`unclassified backend path (fail-safe): ${path}`);
    changedPackages.add(pkg);
  }
  if (changedPackages.size === 0) {
    return { mode: 'none', reason: 'no backend-relevant files in the diff', packages: [], testPattern: '' };
  }

  // One level of reverse dependencies: any source referencing a changed package (dotted or
  // slash form) pulls in its own top-level package.
  const dependants = new Set();
  for (const { path, content } of javaSources) {
    const pkg = backendPackageOf(path);
    if (pkg === null || changedPackages.has(pkg)) continue;
    if ([...changedPackages].some((changed) => referencesPackage(content, changed))) dependants.add(pkg);
  }

  const packages = uniqSorted([...ALWAYS_ON_PACKAGES, ...changedPackages, ...dependants]);
  return {
    mode: 'selected',
    reason: `changed packages: ${[...changedPackages].sort().join(', ')}; dependants: ${
      [...dependants].sort().join(', ') || '(none)'}; always-on: ${ALWAYS_ON_PACKAGES.join(', ')} + FlywayMigrationTest`,
    packages,
    testPattern: surefirePattern(packages),
  };
}

// ── frontend ────────────────────────────────────────────────────────────────────────────

const FRONTEND_CODE_EXT = /\.(js|jsx|ts|tsx|json)$/;

function isFrontendFullTrigger(path) {
  if (path === '.github/workflows/frontend-ci.yml') return true;
  if (path.startsWith('.github/scripts/')) return true;
  // docs/api/** is read by several tests through helpers (statusCatalog.js, apiSurface.js)
  // that the fs-reading heuristic cannot attribute to a test file, so it is FULL, not a
  // targeted pick (PR #1099 review).
  if (path.startsWith('docs/api/')) return true;
  if (!path.startsWith('frontend/')) return false;
  if (path === 'frontend/package.json' || path === 'frontend/package-lock.json') return true;
  if (path.startsWith('frontend/vite.config.') || path.startsWith('frontend/vitest.config.')) return true;
  if (path.startsWith('frontend/src/test/')) return true;
  if (path.startsWith('frontend/.env')) return true;
  // Non-code files (CSS, HTML, assets) are read by tests through fs, which the import graph
  // `vitest --changed` follows cannot see.
  if (!FRONTEND_CODE_EXT.test(path)) return true;
  return false;
}

function isServerContractTrigger(path) {
  return path.startsWith('docs/api/')
    || (path.startsWith('backend/src/main/java/') && path.endsWith('Controller.java'));
}

const BACKEND_MAIN_JAVA = 'backend/src/main/java/';

/**
 * @param {object} input
 * @param {{path: string, content: string}[]} input.testFilesReadingFs
 *        fs-reading test files, paths relative to frontend/, WITH their content (F9)
 */
export function selectFrontendTests({ changedFiles, testFilesReadingFs, isPullRequest, labels, baseRef }) {
  const full = (reason) => ({ mode: 'full', reason, extraTestFiles: [] });
  const universal = universalReason({ isPullRequest, labels, baseRef });
  if (universal) return full(universal);

  const trigger = changedFiles.find(isFrontendFullTrigger);
  if (trigger) return full(`full-suite trigger changed: ${trigger}`);

  const frontendChanged = changedFiles.some((p) => p.startsWith('frontend/'));
  const contractTriggered = changedFiles.some(isServerContractTrigger);

  // F9: fs-reading tests that parse a changed backend Java file (by full path or basename).
  const changedJava = changedFiles.filter((p) => p.startsWith(BACKEND_MAIN_JAVA) && p.endsWith('.java'));
  const javaReaders = testFilesReadingFs
    .filter(({ content }) => changedJava.some((java) => content.includes(java) || content.includes(basename(java))))
    .map(({ path }) => path);

  if (!frontendChanged && !contractTriggered && javaReaders.length === 0) {
    return { mode: 'none', reason: 'no frontend-relevant files in the diff', extraTestFiles: [] };
  }

  // Every fs reader when frontend code changed (the import graph cannot see them); otherwise
  // only the readers tied to the changed backend files.
  const extras = frontendChanged ? testFilesReadingFs.map(({ path }) => path) : [...javaReaders];
  // serverContract.test.js scans every screen through apiSurface.js's readdirSync, so ANY
  // frontend change can break it — the scan sits in a helper the fs-reading heuristic misses.
  if (contractTriggered || frontendChanged) extras.push(SERVER_CONTRACT_TEST);
  return {
    mode: 'changed',
    reason: `${frontendChanged ? `frontend code changed (${testFilesReadingFs.length} fs-reading tests appended)` : 'no frontend change'}; ${
      contractTriggered ? 'API surface changed → serverContract test added' : frontendChanged ? 'serverContract test always added with frontend code' : 'API surface untouched'}; ${
      javaReaders.length} test(s) read a changed backend Java file`,
    extraTestFiles: uniqSorted(extras),
  };
}

// ── CLI ─────────────────────────────────────────────────────────────────────────────────
//
//   node .github/scripts/select-tests.mjs --side backend|frontend --base <sha> \
//        --event <github.event_name> [--base-ref <branch>] [--labels a,b] \
//        [--changed-files p1,p2] [--repo <dir>]
//
// Prints key=value lines for $GITHUB_OUTPUT. `--changed-files` bypasses `git diff` (for local
// smoke tests). Any exception → `mode=full` so a broken picker can never skip tests.

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (!a.startsWith('--')) throw new Error(`unexpected argument: ${a}`);
    out[a.slice(2)] = argv[i + 1];
    i += 1;
  }
  return out;
}

function csv(value) {
  return (value ?? '').split(',').map((s) => s.trim()).filter(Boolean);
}

async function walk(fs, pathMod, dir, keep, acc = []) {
  let entries;
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch (e) {
    if (e.code === 'ENOENT') return acc;
    throw e;
  }
  for (const entry of entries) {
    if (entry.name === 'node_modules') continue;
    const full = pathMod.join(dir, entry.name);
    if (entry.isDirectory()) await walk(fs, pathMod, full, keep, acc);
    else if (keep(full)) acc.push(full);
  }
  return acc;
}

async function main(argv) {
  const [{ promises: fs }, pathMod, { execFileSync }] = await Promise.all([
    import('node:fs'), import('node:path'), import('node:child_process'),
  ]);
  const args = parseArgs(argv);
  const side = args.side;
  if (side !== 'backend' && side !== 'frontend') throw new Error('--side must be backend|frontend');
  const repo = pathMod.resolve(args.repo ?? '.');
  const isPullRequest = args.event === 'pull_request';
  const labels = csv(args.labels);
  const baseRef = args['base-ref'] || undefined;

  let changedFiles;
  if (args['changed-files'] !== undefined) {
    changedFiles = csv(args['changed-files']);
  } else {
    if (!args.base) throw new Error('--base <sha> is required (or --changed-files)');
    // --no-renames: a move out of config/ or common/ must list the SOURCE path too, so the
    // full-suite triggers see it. quotePath=false: non-ASCII paths come back unescaped.
    const diff = execFileSync('git', ['-c', 'core.quotePath=false', 'diff', '--no-renames', '--name-only', args.base, 'HEAD'],
      { cwd: repo, encoding: 'utf8' });
    changedFiles = diff.split('\n').map((s) => s.trim()).filter(Boolean);
  }

  const lines = [];
  if (side === 'backend') {
    const javaFiles = await walk(fs, pathMod, pathMod.join(repo, 'backend/src'), (f) => f.endsWith('.java'));
    const javaSources = await Promise.all(javaFiles.map(async (f) => ({
      path: pathMod.relative(repo, f).split(pathMod.sep).join('/'),
      content: await fs.readFile(f, 'utf8'),
    })));
    const r = selectBackendTests({ changedFiles, javaSources, isPullRequest, labels, baseRef });
    lines.push(`mode=${r.mode}`, `reason=${r.reason}`, `packages=${r.packages.join(',')}`, `test_pattern=${r.testPattern}`);
  } else {
    const FS_RE = /readFileSync|readdirSync|readFile\(|fs\./;
    const roots = [
      [pathMod.join(repo, 'frontend/src'), (f) => /\.test\.(js|jsx)$/.test(f)],
      [pathMod.join(repo, 'frontend/scripts'), (f) => f.endsWith('.test.js')],
    ];
    const testFilesReadingFs = [];
    for (const [root, keep] of roots) {
      for (const f of await walk(fs, pathMod, root, keep)) {
        const content = await fs.readFile(f, 'utf8');
        if (FS_RE.test(content)) {
          testFilesReadingFs.push({
            path: pathMod.relative(pathMod.join(repo, 'frontend'), f).split(pathMod.sep).join('/'),
            content,
          });
        }
      }
    }
    const r = selectFrontendTests({ changedFiles, testFilesReadingFs, isPullRequest, labels, baseRef });
    lines.push(`mode=${r.mode}`, `reason=${r.reason}`, `extra_test_files=${r.extraTestFiles.join(' ')}`);
  }
  process.stdout.write(`${lines.join('\n')}\n`);
}

// Compare REAL paths: `node some/symlink.mjs` gives argv[1] as the symlink while
// import.meta.url is the resolved target, so a plain string compare would silently
// never run main() (F3).
async function isInvokedDirectly() {
  if (typeof process === 'undefined' || !process.argv[1]) return false;
  const [{ realpathSync }, { fileURLToPath }] = await Promise.all([import('node:fs'), import('node:url')]);
  try {
    return realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
}

const invokedDirectly = await isInvokedDirectly();

if (invokedDirectly) {
  try {
    await main(process.argv.slice(2));
  } catch (err) {
    // Fail SAFE: never let a broken picker turn into a green check that ran nothing.
    const reason = `select-tests failed, falling back to the full suite: ${String(err && err.message ? err.message : err).replace(/\s+/g, ' ')}`;
    process.stderr.write(`::warning::${reason}\n`);
    process.stdout.write(`mode=full\nreason=${reason}\npackages=\ntest_pattern=\nextra_test_files=\n`);
  }
}
