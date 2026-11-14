import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// docs/api/status-catalog.json digest reader — the frontend half of the guard whose backend half is
// StatusCatalogContractTest. Node-only (readFileSync): used by vitest only, never shipped to a browser bundle.

/** Resolve a path relative to THIS module. See apiSurface.js's identical helper for why both branches exist:
 * `import.meta.url` is a real `file:` URL under Playwright, but vitest's transform makes it something
 * `fileURLToPath` rejects — the fallback anchors on vitest's root. */
function fromModule(relative) {
  try {
    return fileURLToPath(new URL(relative, import.meta.url));
  } catch {
    return new URL(relative, `file://${process.cwd()}/src/utils/`).pathname;
  }
}

const digestPath = () => fromModule('../../../docs/api/status-catalog.json');

const digest = () => JSON.parse(readFileSync(digestPath(), 'utf8'));

/** `{ TicketStatus: [...], PricingRequestStatus: [...], ... }` — every group the backend digest publishes,
 * straight off the committed file. */
export function statusGroups() {
  return digest().groups;
}
