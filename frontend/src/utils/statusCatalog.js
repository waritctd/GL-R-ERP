import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// docs/api/status-catalog.json digest reader — the frontend half of the guard whose backend half
// is StatusCatalogContractTest. See that class's own Javadoc for why the digest exists (the
// factoryQuoteStatusLabel/READY_FOR_COSTING fossil) and how each backend type is read (reflection,
// split by shape — String-constant class vs Java enum — never source-text parsing).
//
// Node-only (readFileSync): used by vitest only, never shipped to a browser bundle. Same pattern
// as src/api/apiSurface.js, which this deliberately mirrors.

/**
 * Resolve a path relative to THIS module. See apiSurface.js's identical helper for why both
 * branches exist: `import.meta.url` is a real `file:` URL under Playwright, but vitest's
 * transform makes it something `fileURLToPath` rejects — the fallback anchors on vitest's root.
 */
function fromModule(relative) {
  try {
    return fileURLToPath(new URL(relative, import.meta.url));
  } catch {
    return new URL(relative, `file://${process.cwd()}/src/utils/`).pathname;
  }
}

const digestPath = () => fromModule('../../../docs/api/status-catalog.json');

const digest = () => JSON.parse(readFileSync(digestPath(), 'utf8'));

/**
 * `{ TicketStatus: [...], PricingRequestStatus: [...], ... }` — every group the backend digest
 * publishes, straight off the committed file. Each array is already sorted and de-duplicated by
 * StatusCatalogContractTest; this does no further processing so a reader comparing the JSON to
 * this function's output sees the same shape.
 */
export function statusGroups() {
  return digest().groups;
}
