// Build-time stand-in for `mockApi.js`, substituted by vite.config.js whenever
// `VITE_USE_MOCKS !== 'true'`.
//
// `api/index.js` imports the mock statically so `api` can stay a plain synchronous export, which
// meant the mock's demo seed travelled into every production bundle. Rollup shakes out the mock's
// method bodies once `VITE_USE_MOCKS` folds to `false`, but it cannot shake out the module-level
// `const db = createDemoDatabase()` and the `db.commissions = … buildDemoCommissions()` beneath it:
// a call at module scope is a side effect as far as a bundler is concerned. Keeping `db` alive
// keeps demoData.js, demoHr.js, demoSales.js and demoPayroll.js alive with it — measured at ~156KB
// of dist/assets, with 110 of 110 of demoData.js's Thai string literals present in the deployed
// chunk.
//
// A pure-call annotation on the first of those two lines does NOT fix it — measured, 0KB saved —
// because the second is a real mutation of `db`. So the substitution happens at resolve time
// instead, where it is deterministic rather than dependent on a bundler proving purity.
//
// This throws rather than exporting an empty object. Nothing can reach it: `api/index.js` selects
// `hrApi` when mocks are off, and additionally throws at module load if a production build ever has
// them on. But if some future path did reach it, a named failure beats `undefined is not a
// function` from an empty stand-in.
//
// Only the `get` trap throws. `has`/`ownKeys` are left alone so an `in` check or a spread — the
// kind of thing an interop shim might do while merely inspecting the module — degrades quietly
// instead of exploding somewhere unrelated to the actual mistake.
export const api = new Proxy({}, {
  get(_target, property) {
    throw new Error(
      `mockApi is not bundled in this build (VITE_USE_MOCKS !== "true"), so api.${String(property)} `
        + 'does not exist. Run with VITE_USE_MOCKS=true to use the mock API.',
    );
  },
});
