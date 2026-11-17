// The page the user was on before opening มีอะไรใหม่; the feedback form pre-selects its area and sends it.
let last = null;

export function recordPage(path) {
  if (path && !path.split(/[?#]/)[0].startsWith('/whats-new')) last = path;
}

export function getLastPage() {
  return last;
}

export function clearLastPage() {
  last = null;
}
