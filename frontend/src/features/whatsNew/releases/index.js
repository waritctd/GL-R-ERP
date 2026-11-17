import release20261116 from './2026-11-16.js';
import release20261117 from './2026-11-17.js';

/** Every release notice, newest first. Add a new release by adding its module here. */
export const RELEASES = [release20261116, release20261117].sort((a, b) => b.date.localeCompare(a.date));

export function findRelease(id) {
  return RELEASES.find((release) => release.id === id) ?? null;
}

export function latestRelease() {
  return RELEASES[0] ?? null;
}
