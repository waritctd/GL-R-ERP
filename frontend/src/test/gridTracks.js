// Tracks in a Tailwind `grid-cols-[a_b_c]` class (underscores separate tracks), or null when the
// element carries none. A DataTable whose template has fewer or more tracks than columns wraps its
// cells onto a second grid row, which jsdom can't see -- so tests compare the two counts instead.
// Flat tracks only: `repeat(3,…)` counts as 1 and an underscore inside a function (`minmax(0,_1fr)`) as 2.
export function gridTrackCount(className) {
  const match = /(?:^|\s)grid-cols-\[([^\]]+)\]/.exec(className);
  return match ? match[1].split('_').length : null;
}
