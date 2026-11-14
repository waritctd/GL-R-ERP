// Parser for e2e-visual/accepted-changes.txt — the list of snapshots whose pixel change is intended and
// reviewed for ONE specific PR. AN ENTRY ONLY APPLIES TO THE PR IT NAMES. See accepted-changes.txt for the
// format and the rules.

import fs from 'node:fs';

/** Parses snapshot-change approvals scoped to the current PR.
 * @param {string} text     contents of accepted-changes.txt
 * @param {string|number} prNumber  the PR this run belongs to; falsy disables all acceptances
 * @param {Set<string>} [knownSnapshots]  every snapshot name the spec can produce; when
 * @returns {Map<string, string>} snapshot name (no .png) -> reason */
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
