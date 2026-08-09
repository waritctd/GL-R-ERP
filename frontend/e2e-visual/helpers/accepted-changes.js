// Parser for e2e-visual/accepted-changes.txt — the list of snapshots whose pixel
// change is intended and reviewed for ONE specific PR.
//
// See accepted-changes.txt for the format and the rules. The two properties this
// file exists to guarantee:
//
//   1. AN ENTRY ONLY APPLIES TO THE PR IT NAMES. Entries for any other PR are
//      dropped here, so a line left behind after a merge is inert by
//      construction -- it can never exempt a future PR. Pruning the file is
//      housekeeping, not a guarantee anything depends on.
//   2. NO PR NUMBER, NO ACCEPTANCES. A local run, or any context that does not
//      set VISUAL_PR_NUMBER, gets an empty map and therefore full strictness.
//
// Both failure directions are deliberately safe: a typo in the PR number or the
// snapshot name means the entry is NOT honoured, so the job stays red rather
// than silently waving a diff through.

import fs from 'node:fs';

/**
 * @param {string} text     contents of accepted-changes.txt
 * @param {string|number} prNumber  the PR this run belongs to; falsy disables all acceptances
 * @param {Set<string>} [knownSnapshots]  every snapshot name the spec can produce; when
 *   supplied, an entry for THIS PR naming something outside it throws. Entries for other
 *   PRs are never validated -- a stale line pointing at a since-deleted route must stay
 *   harmless, not break the suite.
 * @returns {Map<string, string>} snapshot name (no .png) -> reason
 */
export function parseAcceptedChanges(text, prNumber, knownSnapshots) {
  const accepted = new Map();
  if (!prNumber) return accepted;
  const want = String(prNumber);

  text.split('\n').forEach((raw, i) => {
    const line = raw.trim();
    if (!line || line.startsWith('#')) return;

    const [pr, snapshotRaw, ...rest] = line.split(/\s+/);
    const where = `accepted-changes.txt:${i + 1}`;
    if (!/^\d+$/.test(pr)) {
      throw new Error(`${where}: first field must be a PR number, got ${JSON.stringify(pr)}\n  ${raw}`);
    }
    if (!snapshotRaw) {
      throw new Error(`${where}: missing snapshot name after PR #${pr}\n  ${raw}`);
    }
    if (pr !== want) return; // another PR's entry — inert

    const snapshot = snapshotRaw.replace(/\.png$/, '');
    if (knownSnapshots && !knownSnapshots.has(snapshot)) {
      throw new Error(
        `${where}: "${snapshot}" is not a snapshot this spec produces.\n` +
          `  Check the spelling against the name CI reports (no .png, no -chromium-linux suffix).\n` +
          `  ${raw}`,
      );
    }
    // A reason is required: the whole point is that a human looked and said why.
    const reason = rest.join(' ').replace(/^[—–-]+\s*/, '').trim();
    if (!reason) {
      throw new Error(`${where}: give a reason after the snapshot name — "<PR> <snapshot> — why"\n  ${raw}`);
    }
    accepted.set(snapshot, reason);
  });

  return accepted;
}

/** Reads the file if present; a missing file simply means "nothing accepted". */
export function loadAcceptedChanges(filePath, prNumber, knownSnapshots) {
  if (!prNumber || !fs.existsSync(filePath)) return new Map();
  return parseAcceptedChanges(fs.readFileSync(filePath, 'utf8'), prNumber, knownSnapshots);
}
