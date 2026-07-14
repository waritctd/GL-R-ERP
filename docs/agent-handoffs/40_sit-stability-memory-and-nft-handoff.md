# Agent Handoff

## Task
Record the completed SIT/stability memory after the backend deploy and final production-polish pass,
then prepare the next agent/thread for NFT / next-feature work.

## Branch
`main`

## Base Commit
`b02d17c` (main tip before the final stability push)

## Current Commit
`509cf09` - `Stabilize backend and frontend validation`

Pushed to `origin/main`. GitHub accepted the direct push while reporting branch-rule bypass notices
for "Changes must be made through a pull request" and required `dependency-review`.

## Agent / Model Used
Codex GPT-5

## Scope

### In Scope
- Backend deploy failure troubleshooting.
- Backend sanity/stability fixes.
- Frontend integration fixes found during SIT.
- Production build/test stabilization.
- Local dependency repair needed to make Vitest reliable.
- Commit and push validated changes to `main`.
- Capture this memory for the next agent.

### Out of Scope
- New ERP features.
- Broad backend/frontend rewrites.
- Refactoring untracked `tools/`.
- Changing API contracts unless a confirmed bug required it.
- Starting NFT / next-feature implementation in this handoff.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/common/ApiExceptionHandler.java`: added explicit 405 handling for unsupported HTTP methods.
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeService.java`: converts duplicate employee code insert failures to `409 Conflict`.
- `backend/src/test/java/th/co/glr/hr/employee/EmployeeControllerTest.java`: covers unsupported method behavior.
- `backend/src/test/java/th/co/glr/hr/employee/EmployeeServiceTest.java`: covers duplicate employee code conflict behavior.
- `frontend/src/App.jsx`: waits for `/auth/me` session restore before rendering login routes, preventing auth flicker.
- `frontend/src/App.test.jsx`: added auth-restore regression coverage.
- `frontend/src/components/common/Button.jsx`: explicit React import for current Vitest/runtime path.
- `frontend/src/components/common/Icon.jsx`: explicit React import for JSX render path.
- `frontend/src/components/common/RouteFallback.jsx`: explicit React import for JSX render path.
- `frontend/src/components/common/Skeleton.jsx`: explicit React import for JSX render path.
- `frontend/src/features/auth/LoginPage.jsx`: explicit React import for JSX render path.
- `frontend/src/features/ceoSettings/CeoSettingsPage.jsx`: stabilized `useEffect` dependencies with `useCallback`.
- `frontend/src/features/commissions/CommissionPage.jsx`: stabilized load effect dependencies and removed unnecessary memoization.
- `frontend/src/features/dashboard/TicketDashboard.jsx`: stabilized load effect dependencies.
- `frontend/src/features/deposits/DepositNoticePage.jsx`: stabilized form/population effects.
- `frontend/src/features/tickets/TicketDetailPage.jsx`: stabilized ticket/attachment load effects.
- `frontend/src/features/tickets/TicketListPage.jsx`: stabilized ticket list load effects.
- `frontend/src/index.css`: constrained Tailwind v4 utility scanning to `frontend/src` with `source(none)` + `@source "."`.
- `frontend/vite.config.js`: disables the Tailwind Vite plugin in test mode only; dev/build still use Tailwind.

## Commands Run
```bash
# Backend validation
cd backend && ./mvnw -Dtest=EmployeeServiceTest,EmployeeControllerTest test
cd backend && mvn -q -DskipTests package
cd backend && ./mvnw test

# Frontend validation
cd frontend && npm run lint
cd frontend && npm test -- App.test.jsx
cd frontend && npm test
cd frontend && npm run build

# Dependency/local filesystem repair
cd frontend && npm install
rm -rf frontend/node_modules
cd frontend && npm install

# SIT stack cleanup
docker compose -p glr-erp-sit ps
docker compose -p glr-erp-sit down -v --remove-orphans

# Git
git add <validated backend/frontend files>
git commit -m "Stabilize backend and frontend validation"
git push origin main
```

## Test / Build Results
- Backend targeted tests: pass, `20` tests, `0` failures/errors.
- Backend package: pass.
- Backend full suite with Docker/Testcontainers access: pass, `319` tests, `0` failures/errors/skips.
- Backend full suite without Docker access: failed only because sandbox blocked Testcontainers Docker socket access; rerun with Docker access passed.
- Frontend lint: pass.
- Frontend focused App auth-restore test: pass, `2` tests.
- Frontend full test suite: pass, `18` files / `79` tests.
- Frontend production build: pass, `1979` modules transformed.
- npm audit after dependency reinstall: `0` vulnerabilities.

## Decisions Made
- Kept API contracts stable. The backend changes only normalize error behavior for confirmed bug paths.
- Returned duplicate employee code as `409 Conflict`; this is safer for frontend UX than an unexpected 500.
- Returned unsupported methods as `405 Method not allowed`; this avoids false 500s.
- Held the login screen until auth restore completes to avoid a visible login flash for valid sessions.
- Scoped Tailwind v4 source scanning explicitly because the production build was hanging while scanning too broadly.
- Disabled Tailwind only in Vite test mode because unit tests do not need Tailwind generation and Vitest has a separate config path.
- Reinstalled `frontend/node_modules` after detecting macOS `compressed,dataless` placeholder files that caused `jsdom` and Vitest to hang.
- Left untracked `tools/` untouched.

## Assumptions
- "NFT next" means the next feature/thread after SIT. The next agent should confirm the exact NFT scope before editing code.
- On-prem production remains the deployment target; future backend work should keep local/staging/on-prem assumptions explicit.
- `main` should remain deployable from commit `509cf09`.

## Known Risks
- `tools/` is still untracked and was intentionally not included in the stability commit.
- GitHub accepted a direct push to `main` with branch-rule bypass messages. Future work should preferably use PR flow unless the user explicitly asks for direct main pushes.
- The previous in-app browser runtime lacked `fetch`/`XMLHttpRequest`, so browser-rendered API E2E could not be trusted there. API/session behavior was verified through backend/Vite proxy and automated tests instead.
- Local macOS/iCloud-style dataless files can break Node/Vitest in this workspace. If tests hang at startup, first check `ls -lO frontend/node_modules/...` for `compressed,dataless`, then reinstall dependencies from lockfile.

## Things Not Finished
- No NFT / next-feature implementation was started.
- No PR was opened for `509cf09` because the user asked to push to `main`.
- No release tag was created.

## Recommended Next Agent
Codex implementation agent or Claude planning agent, depending on the NFT scope. Start with scope clarification, not code.

## Exact Next Prompt
```text
You are taking over GL-R-ERP after the SIT/stability pass.

First read:
- docs/agent-handoffs/00_MASTER_CONTEXT.md
- docs/agent-handoffs/40_sit-stability-memory-and-nft-handoff.md

Current stable baseline:
- main commit 509cf09, pushed to origin/main
- backend full suite passed: 319 tests, 0 failures/errors
- frontend lint/tests/build passed: 18 files / 79 tests

Rules:
- Keep main deployable.
- Do not start broad rewrites.
- Do not touch untracked tools/ unless explicitly instructed.
- Assume production backend/database is on-prem.
- Preserve existing API contracts unless a confirmed bug requires a change.

Task:
Prepare the NFT / next-feature work. Before editing code, confirm what "NFT" means in this product context, identify the smallest safe implementation slice, list impacted backend/frontend/database files, and propose the validation plan. If scope is clear from the user, implement only that small slice and run the relevant tests/builds.
```
