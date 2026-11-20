import releaseV2 from './v2.0.0.js';
import releaseV2_1 from './v2.1.0.js';

/** Every release notice, newest first (same day: higher version first). Add a new release by adding its module here, named for its version. */
export const RELEASES = [releaseV2, releaseV2_1].sort((a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id, 'en', { numeric: true }));

export function findRelease(id) {
  return RELEASES.find((release) => release.id === id) ?? null;
}

export function latestRelease() {
  return RELEASES[0] ?? null;
}
