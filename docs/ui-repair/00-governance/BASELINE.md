# UI Repair Baseline

The recorded state of the GL-R ERP frontend at the start of the repair. This is
**evidence, not assumptions** — facts here came from reading the code and running
the commands. Where something could not be verified, it says so.

- **Recorded:** 2026-07-25
- **Branch:** `refactor/ui-foundation-phases-0-3`
- **Base commit:** `9640327` (`9640327ecb635012c325ee8eb547da04fb52e42c`) —
  _"fix(attendance): order punch list ascending + add source column"_
- **Method:** static inspection (3 parallel exploration passes) + the baseline
  commands in the [results](#baseline-check-results) section below.

## Stack & exact versions

Source: `frontend/package.json` (name `glr-hr-react`, version `0.1.0`, ESM,
Node `^20.19.0 || >=22.12.0`). Plain JavaScript — **there is no TypeScript and no
typecheck step.**

| Area | Package | Version |
|------|---------|---------|
| UI | react / react-dom | `^18.3.1` |
| Build | vite | `^8.1.2` |
| Styling | tailwindcss / @tailwindcss/vite | `^4.3.2` (no `tailwind.config`; Vite plugin + `@theme`) |
| Variants | class-variance-authority | `^0.7.1` |
| Class merge | clsx / tailwind-merge | `^2.1.1` / `^3.6.0` |
| Tables | @tanstack/react-table | `^8.21.3` |
| Server state | @tanstack/react-query | `^5.101.2` |
| Forms | react-hook-form / @hookform/resolvers | `^7.81.0` / `^5.4.0` |
| Validation | zod | `^4.4.3` |
| Routing | react-router-dom | `^7.18.1` |
| Icons | lucide-react | `^1.23.0` |
| Unit tests | vitest / @testing-library/react / jsdom | `^3.2.4` / `^16.1.0` / `^29.1.1` |
| E2E | @playwright/test | `^1.61.1` |
| Lint | eslint (+ react, react-hooks, jsx-a11y plugins) | `^9.17.0` (flat config) |

## Commands (real scripts, all under `frontend/`)

There is **no root `package.json`** — every script lives in `frontend/`.

| Script | Command | Notes |
|--------|---------|-------|
| `dev` | `vite --host 127.0.0.1 --port 5174 --strictPort` | human dev server |
| `build` | `vite build` | production build |
| `preview` | `vite preview --host 127.0.0.1 --port 4174 --strictPort` | |
| `lint` | `eslint src` | flat config `frontend/eslint.config.js` |
| `test` | `vitest run` | config `frontend/vitest.config.js`, jsdom, setup `src/test/setup.js` |
| `test:e2e` | `playwright test` | config `frontend/playwright.config.js`; own mock server on `127.0.0.1:5250` with `VITE_USE_MOCKS=true`, 1 worker, chromium only |

`frontend/vite.config.js` also proxies `/api` → `http://localhost:8080`.

## CSS / Tailwind entry points

- App entry `frontend/src/main.jsx` imports exactly one stylesheet:
  `frontend/src/index.css`.
- `frontend/src/index.css` (**124 lines**) — Tailwind 4 CSS-first bootstrap:
  `@import "tailwindcss/theme.css" layer(theme)`, `@import "./styles.css"
  layer(legacy)`, `@import "tailwindcss/utilities.css" layer(utilities)`, plus a
  `@theme static { … }` token block.
- `frontend/src/styles.css` (**2213 lines**) — the legacy global stylesheet,
  imported into `layer(legacy)`, being progressively retired.
- These are the **only two** `.css` files under `frontend/src`. Tailwind is wired
  via `@tailwindcss/vite` in `frontend/vite.config.js`; there is no
  `tailwind.config.*` or `postcss.config.*`.

## Shared UI components

- `frontend/src/components/common/` — ~26 primitives: Avatar, Breadcrumbs, Button,
  CollapsibleSection, ConfirmDialog, DataTable, DesktopOnlyNotice, EmptyState,
  ErrorBoundary, FieldList, FileUploadField, FormField, Icon, InfoTip, Layout,
  Modal, NotificationBell, PageHeader, RouteFallback, Skeleton, StatCard,
  StatusBadge, Toast.
- `frontend/src/components/layout/` — AppShell, Sidebar, UserMenu.
- Class helper: `frontend/src/utils/cn.js` (wraps `clsx` + `tailwind-merge`).
- **`class-variance-authority` is currently used in exactly one file** —
  `frontend/src/components/common/Button.jsx`. Broadening CVA-driven variants to
  more primitives is latent Phase-3 work, not yet done.

## Feature directories

20 domains under `frontend/src/features/`: attendance, auth, catalog, ceoSettings,
commissions, dashboard, deposits, employees, finance, leave, overtime, payroll,
pricingRequests, procurement, profile, profileRequests, requests, sales,
specialmoney, tickets. Other top-level `src/` dirs: `api/`, `app/`, `components/`,
`data/`, `hooks/`, `utils/`, `test/`.

## Roles (as found in code)

Role literals in `frontend/src/app/` + `frontend/src/api/routes.js`: **`ceo`,
`import`, `sales`, `sales_manager`, `hr`, `account`, `employee`, `warehouse`,
`qc`**. "Division manager" is **not** a distinct role literal — it is *derived*
(`role === 'employee'` + a `manager` flag) via `isDivisionManager`.

Permission model: `frontend/src/app/permissions.js` (`hasPermission`,
`canAccessPath`, `allowedRoute`, `PATH_GUARDS`), the `ROLE_PERMISSIONS` matrix in
`frontend/src/api/routes.js`, and the `RequireAccess` router guard. **Frontend
permission checks are not authoritative** — the Java service is the source of truth
(see `CLAUDE.md`).

## Route families

Single `<Routes>` in `frontend/src/App.jsx` (react-router-dom 7, lazy-loaded) under
one `<AppShell>` layout. Families: tickets/deals, pricing-requests,
procurement/factory-POs, commissions, finance, payroll, catalog/price-import,
employees, profile/requests, attendance, leave, employee-requests (OT + welfare),
ceo-settings, hr. The index route is role-branched (per-role Overview). Sales
routes are gated behind `SALES_ENABLED` (`frontend/src/app/features.js`); most
routes sit behind `RequireAccess`; `/attendance` is ungated; `*` → redirect to `/`.

## Responsive mechanisms — known consistency risk

Three **unaligned** mechanisms, all hardcoding the same `720px` mobile breakpoint
with no shared token:

1. `frontend/src/hooks/useIsMobile.js` — `matchMedia('(max-width: 720px)')`
   (used in ~9 files: DataTable, Button, EmployeeListPage, PayrollPage,
   CatalogSearchPage, PriceImportPage, AccountFinancePage, and two dashboards).
2. `frontend/src/styles.css` — `@media (max-width: 720px)` blocks (9 `@media`
   total, incl. `900px`/`1040px` and 4 `prefers-reduced-motion`).
3. `frontend/src/components/common/Button.jsx` — a `max-[720px]:min-h-[44px]`
   Tailwind arbitrary variant.

Plus `DesktopOnlyNotice` (a "no mobile representation" fallback, used in
PayrollPage) and Tailwind `sm:/md:/lg:` prefixes across feature JSX. **Flag for
Phase 3:** the `720px` breakpoint should become a single shared token.

## Test coverage (structure)

- **Unit / component:** 64 files matching `src/**/*.test.{js,jsx}` (Vitest
  `include` only picks up `*.test.{js,jsx}`; no `.spec.` files under `src`).
- **E2E:** 7 Playwright specs in `frontend/e2e/` — `auth`, `commission`,
  `deal-creation`, `deposit-fulfilment-close`, `hr`, `pcr-chain`, `rbac` (plus a
  non-spec helper `e2e/helpers/auth.js`). All run against the mock backend.

Coverage numbers (line/branch %) are **not measured** here — no coverage run was
part of this baseline. File counts are structural facts, not a quality claim.

## Baseline check results

Commands run 2026-07-25 from repo root via `frontend/`'s scripts (using `npm ci`
per the known "corrupt node_modules" hazard), on Node `v25.8.2` / npm `11.14.1`
(satisfies the `>=22.12.0` engines range). Results are recorded verbatim from the
actual run.

| # | Command | Result | Duration | Classification |
|---|---------|--------|----------|----------------|
| 1 | `npm --prefix frontend ci` | ✅ exit 0 | 4s | — |
| 2 | `npm --prefix frontend run lint` | ✅ exit 0 — **1 warning, 0 errors** | 7s | pre-existing (warning) |
| 3 | `npm --prefix frontend test` | ✅ exit 0 — **559 passed / 64 files** | 18s (vitest 16.16s) | — |
| 4 | `npm --prefix frontend run build` | ✅ exit 0 — 315 modules, 73 assets, real bundle | 1s (vite 214ms) | — |
| 5 | `npm --prefix frontend exec -- playwright install` | ✅ exit 0 — chromium downloaded | 180s | environment (one-time) |
| 6 | `npm --prefix frontend run test:e2e` | ✅ exit 0 — **25 passed** | 61s | — |

**Every baseline check passed.** These results are from executed runs, not
assumptions.

Failure classification key: **environment** (tooling/host, not the code),
**pre-existing** (a code/test issue that predates Phase 0), **phase-0-regression**
(caused by the Phase 0 files — none, since Phase 0 adds only docs).

### Notes & failures

- **No failures.** All six steps returned exit 0.
- **Lint — 1 pre-existing warning (not an error, does not fail the build):**
  `frontend/src/features/payroll/PayrollPage.jsx:312` —
  `react-hooks/exhaustive-deps`: "React Hook useEffect has a missing dependency:
  'load'." Predates Phase 0 (Phase 0 touched no JSX). Left as-is; not a Phase 0
  regression. A candidate cleanup for a future frontend hardening pass, not this
  effort.
- **E2E "drift is logged, not asserted":** the `rbac.spec.js` oracle deliberately
  logs role/path allow-list drift between the live oracle and
  `docs/ux-ui-audit/data/shoot-manifest.json` without failing. This is by design
  (the spec passes); noted so a future reader doesn't mistake the logged JSON for a
  failure.
- **Build duration** is genuinely ~0.2s of Vite work (Vite 8 / esbuild); the "1s"
  is wall-clock rounding, and `frontend/dist/` was verified to contain a fresh
  `index.html` + 73 hashed assets.

## Known limitations / not verified

- **Authorization is not verified here.** All role/permission facts above are read
  from frontend code; the mock is not authoritative. No real-DB integration test
  was run as part of this baseline (Phase 0 changes no authz).
- **No coverage percentages** were measured — only test-file counts.
- **E2E runs against mocks only** (`VITE_USE_MOCKS=true`), so anything
  permission-shaped it exercises is an approximation, not production behaviour.
- **Concurrency caveat:** the repo has many live git worktrees; this baseline
  reflects the `refactor/ui-foundation-phases-0-3` checkout at commit `9640327` at
  the recorded time and may drift if other branches merge.
