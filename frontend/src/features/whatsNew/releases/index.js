import release20261116 from './2026-11-16.js';

/** Every release notice, newest first. Add a new release by adding its module here. */
export const RELEASES = [release20261116].sort((a, b) => b.date.localeCompare(a.date));

export function findRelease(id) {
  return RELEASES.find((release) => release.id === id) ?? null;
}

export function latestRelease() {
  return RELEASES[0] ?? null;
}
