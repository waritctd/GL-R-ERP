# Frontend

## Overview

The React portal that staff use: HR self service, payroll, and the sales pipeline. It is plain JavaScript (no TypeScript), built with Vite, and deployed by Vercel from `main`. It can run against the real backend or against a mock API that lives in the browser.

## Key files

| File | Owns |
|---|---|
| `src/App.jsx` | The single route table. Pages are lazy imports wrapped in `RequireAccess` |
| `src/app/permissions.js`, `src/app/roles.js` | Route guards (`PATH_GUARDS`, `allowedRoute`) and role mapping |
| `src/app/features.js` | Build time switches: `SELF_SERVICE_ONLY`, `MAINTENANCE`, sales on or off |
| `src/api/client.js` | The `fetch` wrapper. Cookie session, `X-XSRF-TOKEN` on writes, `ApiError` |
| `src/api/hrApi.js`, `src/api/mockApi.js`, `src/api/index.js` | Real API, mock API, and the switch between them |
| `src/api/routes.js` | URL map and `ROLE_PERMISSIONS` |
| `src/components/common/` | Shared UI: `Button`, `DataTable`, `SafeForm`, `Modal`, `ConfirmDialog`, `ThaiDateInput` |
| `src/features/<domain>/` | One folder per domain, with tests next to the code |
| `src/utils/format.js` | Thai dates, money, Bangkok time helpers |
| `src/index.css` | Design tokens, breakpoints, and the only CSS reset |
| `e2e-real/` | Playwright tests against the real stack (the only e2e suite) |

## Commands

Run everything from `frontend/`. Guard tests read source files by path and fail from any other folder.

```bash
VITE_USE_MOCKS=true npm run dev            # mock mode, http://localhost:5174
npm run dev                                # real backend on http://localhost:8080
npm run lint                               # ESLint with the custom rules below
npx vitest run src/features/leave          # targeted unit tests
npm test                                   # all unit tests
npm run build
npm run test:e2e                           # Playwright, needs the database and backend running
UI_REACHABLE_UPDATE=1 npx vitest run src/api/serverContract.test.js   # regenerate docs/api/ui-reachable.json
```

## Conventions

- Server data goes through TanStack Query with keys from `src/api/queryKeys.js`. Forms use react-hook-form with zod.
- Every method added to `hrApi.js` needs a twin in `mockApi.js` with the same shape (`contract.test.js` checks this). Name the Java class the mock mirrors.
- The mock is not the authority on permissions. Read access rules from the Java service.
- Use the shared components before writing new markup. Use `SafeForm` for every form, never a raw `<form>`.
- `Button` accepts only the variants in `Button.jsx`. A new variant must be added there and in `eslint.config.js`.
- `DataTable` needs a `gridClassName`.
- Use the named breakpoints from `index.css` (`mobile:`, `tablet:`, `nav-drawer:` and so on). Never write `max-[720px]:` style variants.
- Colours and spacing come from the tokens in `index.css`. Do not add one off values.
- UI copy is Thai. Sales screens say งาน, never ดีล (`salesWordingNgan.test.js` scans for it).
- Dates show Buddhist era years through `ThaiDateInput` and `formatThaiDate`. The stored value stays ISO `YYYY-MM-DD`. Use `bangkokTodayIso` for today.
- Design for phone width first. A sales rep should finish a task in a few taps, from anywhere.

## Gotchas

- `Button` with an unknown variant, and `DataTable` without `gridClassName`, throw in dev and in Vitest.
- Builds are locked to the self service surface unless `VITE_SELF_SERVICE_ONLY=false`. `.env.development` unlocks it, so local dev shows more than production does. Set it to `true` to see what users see.
- `VITE_USE_MOCKS=true` in a production build throws on purpose.
- `frontend/.env.production` is only for the maintenance page. Do not add one for anything else, and never carry it into `develop`.
- Vercel reads `frontend/vercel.json`. The root `vercel.json` must stay the same as it.
- The app does not import Tailwind preflight. The base layer in `index.css` is the only reset, so do not remove it.
- The stage list in `src/data/dealStageCatalog.js` must match the backend. `stageCatalog.test.js` compares the two.
- Open the dev server as `localhost`, not `127.0.0.1`, or the session cookie is not sent.
- A quotation with `isSample` never becomes an order, a billing source or a revise target. Filter it out of any picker or action that chooses a quotation. Screens call it `ใบเสนอราคาสำหรับสั่งตัวอย่าง`.
- Screenshots for the What's New page go in `public/whats-new-img/`, never a folder named `whats-new`.

## Agent skills

Three skills cover frontend work here. Reach for these first.

- [ui-revamp](../.agents/skills/ui-revamp/): vendored, audits and fixes existing UI that looks off or breaks on a phone
- [frontend-design](../.agents/skills/frontend-design/): `julianoczkowski/designer-skills`, builds a new screen or component
- [playwright-best-practices](../.agents/skills/playwright-best-practices/): vendored, how to write and fix the tests in `e2e-real/`

_Drafted by /audit from the repo, worth a quick human pass. Edit freely: once a line stops matching this draft, later runs treat it as curated and will flag rather than overwrite it._
