// Browser-response helpers for the real-backend suite.
//
// Distinct from helpers/api.js on purpose: that module drives Playwright's APIRequestContext
// (`page.request`, `apiSessionFor`) straight at Spring, and its APIResponse bodies are fully
// buffered by the Playwright server — they are immune to everything below. This module is about
// responses the BROWSER issued, observed via page.waitForResponse, which are not buffered and
// are read lazily over CDP.
//
// ── Why the body must be read eagerly ────────────────────────────────────────
// A page.waitForResponse() handle is a *reference*, not a copy. Calling .json()/.body()/.text()
// on it issues Network.getResponseBody, and Chromium serves that from a buffer scoped to the
// document that made the request. When that document is replaced, the buffer is evicted and the
// read fails:
//
//   Protocol error (Network.getResponseBody): No resource with given identifier found
//   Response body is not available for a response that was navigated away from.
//   Read response.body() before triggering any navigation.
//
// That is a real failure this suite hit — smoke.spec.js's employee-directory test, CI run
// 31264054989 (job 93119227871) on 2026-08-08, green on re-run. The shape that produced it:
//
//   const [response] = await Promise.all([page.waitForResponse(pred), page.goto('/employees')]);
//   const body = await response.json();   // ← read only after goto() resolved on `load`
//
// The trap is that the matched response need not belong to the page being navigated TO. Both
// smoke tests log in first, and this app fetches at shell level: App.jsx:126 mounts useHrData,
// whose employees query (useHrData.js:22) fires GET /api/employees the moment an HR user
// authenticates — on the `/` landing, before the test navigates anywhere. SalesOverview.jsx:92
// does the same with GET /api/tickets for sales. loginAs() returns as soon as the account menu
// is visible, which is well before those queries settle, so the waiter registered immediately
// afterwards can bind to the LANDING's still-in-flight response. page.goto() then discards that
// document and its buffer, and the deferred read loses. It usually wins — the landing fetch
// normally settles during loginAs() — which is exactly why it read as an intermittent flake
// rather than a broken test.
//
// captureJson closes that window by reading the body inside the waitForResponse predicate. The
// predicate runs the instant Chromium reports the response — before Playwright resolves the
// waiter, and before the caller's navigation has had a chance to commit — which is the earliest
// point the API exposes and the "read the body before triggering any navigation" the error asks
// for. Callers get plain data, so there is no live Response handle left to misuse afterwards.

/**
 * Waits for a browser-issued response matching `matches` and returns its status and parsed JSON,
 * buffered eagerly so a concurrent navigation cannot evict the body.
 *
 * Prefer this over `waitForResponse` + a later `.json()` for anything raced against `page.goto()`
 * or another navigation. Returns `{ status, json }` rather than the Response itself, deliberately.
 *
 * @param {import('@playwright/test').Page} page
 * @param {(response: import('@playwright/test').Response) => boolean} matches
 * @param {{ timeout?: number }} [options] forwarded to page.waitForResponse
 * @returns {Promise<{ status: number, json: any }>}
 */
export function captureJson(page, matches, options) {
  let captured;
  return page
    .waitForResponse(async (response) => {
      if (!matches(response)) return false;
      try {
        captured = { status: response.status(), json: await response.json() };
      } catch (cause) {
        // Without this the caller sees a bare CDP protocol error with no URL in it, which is
        // what made the original flake expensive to diagnose. Name the request instead.
        throw new Error(
          `Could not buffer the body of ${response.request().method()} ${response.url()} ` +
            `(status ${response.status()}): ${cause.message}`,
          { cause }
        );
      }
      return true;
    }, options)
    .then(() => captured);
}
