# Agent Handoff

## Task
Add backend OpenAPI documentation (DoD #6 / audit P2-1): a springdoc-generated OpenAPI
contract + Swagger UI, exposed **surgically** under the existing default-deny `SecurityConfig`
(mirroring the actuator-health permit), with an integration test proving the docs are reachable
and that a protected endpoint still 401s (default-deny not widened).

## Branch
`backend/openapi-docs`

## Base Commit
`98db5aa` (clean `main`)

## Current Commit
Not committed — changes left in the working tree pending owner approval to commit/PR.

## Agent / Model Used
Planned + reviewed/verified: Claude Opus. Implemented: Claude Sonnet.

## Scope

### In Scope
- Add `springdoc-openapi-starter-webmvc-ui` (backend only).
- Keep the OpenAPI/Swagger paths under default-deny (auth-gated, NOT anonymously readable).
- Label the contract with basic API metadata.
- One integration test mirroring the existing actuator-health/security tests.

### Out of Scope
- Per-controller `@Operation`/`@Schema` annotations (springdoc defaults are enough for v0.1.0).
- Prod-gating / auth-gating the docs (see Known Risks — a release-time decision).
- Any CI change (Testcontainers `mvnw verify` already runs the new test).
- Any frontend or business-logic change.

## Files Changed
- `backend/pom.xml` — added `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3` (version pinned
  explicitly; springdoc is not in the Spring Boot 4.x BOM, same as poi-ooxml/pdfbox here).
- `backend/src/main/java/th/co/glr/hr/config/SecurityConfig.java` — the docs paths are deliberately
  left OFF the allowlist (a comment documents why) so they fall under `.anyRequest().authenticated()`.
  No permit line was added; nothing else touched.
- `backend/src/main/java/th/co/glr/hr/config/OpenApiConfig.java` (new) — `@Configuration` with one
  `@Bean OpenAPI` setting title "GL-R HR Portal API", version "0.1.0", short description.
- `backend/src/test/java/th/co/glr/hr/config/OpenApiDocsIntegrationTest.java` (new) — mirrors the
  `SecurityAuthorizationIntegrationTest` session pattern. Tests: `apiDocsRequireAuthentication`
  (anonymous `GET /v3/api-docs` → 401) and `authenticatedUserCanReadApiDocs` (with a session →
  200, `$.openapi` exists, `$.info.title` == "GL-R HR Portal API").

## Commands Run
```bash
cd backend && ./mvnw -B clean verify   # run by the implementer AND independently re-run by the Opus reviewer
```

## Test / Build Results
- Backend `./mvnw -B clean verify`: **BUILD SUCCESS** (independently re-run by reviewer).
- Tests: **284 run / 0 failures / 0 errors / 0 skipped** (up from 282 — the 2 new tests ran; Docker
  was available so the Postgres-gated integration test was NOT skipped).
- Jacoco ratchet: **met** — measured line coverage 0.5201 vs the 0.51 floor.
- Frontend: not applicable (backend-only change).

## Decisions Made
- Version pinned to springdoc **3.0.3** (the 3.x line targets Spring Boot 4.x; v3.0.0 → Boot 4.0.0,
  v3.0.3 → Boot 4.0.5; compatible with this project's Boot 4.1.0).
- **Docs are auth-gated, not public** (owner decision, 2026-07-07). Rather than allowlist the docs
  paths, they stay under default-deny so reading the contract / enumerating endpoints requires an
  authenticated session. This satisfies DoD #6, keeps docs always in-sync, and removes the anonymous
  information-disclosure surface — with zero added build machinery. (Superseded the handoff-11
  "expose publicly" wording after weighing that this is an internal HR portal with no external API
  consumers yet — the frontend hand-mirrors routes in `api/routes.js`.)
- Deferred: publishing a static OpenAPI contract to an external host (Redocly/SwaggerHub/GitHub
  Pages) — only worth the extra CI/build wiring once an external integrator needs the contract.
- Added a small `OpenApiConfig` bean so the generated contract is labeled rather than "OpenAPI
  definition" default.

## Assumptions
- Docker/Testcontainers is available in CI and locally so the integration test runs (matches the
  existing actuator/security integration tests' gate).

## Known Risks
- Information-disclosure risk from public docs is **resolved** — the docs now require an
  authenticated session (verified by `apiDocsRequireAuthentication`).
- springdoc 3.0.3 is validated against Boot 4.0.5; we run 4.1.0. The full context boots and the
  contract serializes correctly in the integration test, so no compatibility issue observed.

## Things Not Finished
- Nothing within scope. (Optional future polish: richer per-endpoint annotations — not needed for
  v0.1.0.)

## Recommended Next Agent
Owner decision: commit + open PR for `backend/openapi-docs`, then merge after CI. After merge, the
next DoD-gating branch is `docs/v0.1-cleanup` (DoD #9): `docs/README.md` index, archive the frozen
sales planning docs, prune merged branches. (`.env.example` already exists for both apps.)

## Exact Next Prompt
```
Continue GL-R-ERP v0.1.0 stabilization. `backend/openapi-docs` is merged (DoD #6 done).
Start `docs/v0.1-cleanup` (DoD #9 / audit P2-5 docs part): branch off clean `main`, add
docs/README.md as an index, move the frozen-sales planning docs (docs/M0_SURVEY.md,
docs/QUOTATION_AND_REVISION_PLAN.md, docs/TICKET_DASHBOARD_PLAN.md, docs/quotation_template_source.xlsx)
into docs/archive/, and prune the ~10 local + several remote branches already merged into main.
Note: .env.example already exists for frontend and backend — do not recreate. Keep the diff
docs-only; no code changes. Then the remaining gates are the desktop-label + sales-visibility
decisions and the v0.1.0 tag — ask the owner before tagging.
```
