# Handoff: Sales Pricing Step 2 - Factory Quotes and Costing

Date: 2026-07-20
Branch: `feat/sales-factory-quote-costing`
Base before work: `ba9836f` (`feat/sales-pricing-request-foundation`, stacked on latest `origin/main` at the start of the task)
Primary migration: `V61__factory_quote_costing_foundation.sql`

## Scope Implemented

- Added the Step 2 backend foundation for Import-owned factory quote requests, factory response revisions, explicit ready-for-costing marking, costing drafts, recalculation, and explicit CEO submission.
- Preserved Step 1 deal/ticket behavior: no automatic deal stage/status changes and no mutation of legacy ticket item price fields during factory quote/costing work.
- Preserved confidentiality boundaries: Sales and Sales Manager cannot read raw factory quote/costing data; Import and CEO can read the raw quote/costing history.
- Added frontend route/API/query key coverage and mock API behavior for the new Step 2 endpoints.

## Business Rules Captured

- Pricing request items are grouped by their fixed `factory` value when Import generates factory email drafts.
- Import must explicitly send factory requests, record responses, start negotiation, and mark a quote revision ready for costing.
- A later factory response supersedes the previous current revision in the same quote chain.
- Costing drafts are created only after all active fixed factories have current responses marked `READY_FOR_COSTING`.
- Recalculate is explicit and may happen multiple times; it never submits automatically.
- A new factory revision marks open costings stale. Stale costings cannot be submitted until recalculated.
- Submit to CEO is explicit. Submitted costings are immutable.
- Submitted costing moves the pricing request to `READY_FOR_CEO_REVIEW` and notifies CEO.

## Backend Files

- `backend/src/main/resources/db/migration/V61__factory_quote_costing_foundation.sql`
  - Adds `sales.factory_quote`, `sales.factory_quote_item`, `sales.pricing_costing`, and `sales.pricing_costing_item`.
  - Adds quote/costing sequences, quote revision constraints, current-quote uniqueness, open-draft uniqueness, and pricing request Step 2 statuses.
  - Adds catalog snapshot placeholders on `sales.pricing_request_item`.
- `backend/src/main/java/th/co/glr/hr/factoryquote/*`
  - Adds controller, service, repository, DTO/request records, and status enum for factory quote lifecycle.
- `backend/src/main/java/th/co/glr/hr/pricingcosting/*`
  - Adds controller, service, repository, DTO/request records, and status enum for costing lifecycle.
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestStatus.java`
  - Adds Step 2 statuses/transitions.
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestEventKind.java`
  - Adds Step 2 event kinds.
- `backend/src/main/java/th/co/glr/hr/notification/NotificationRepository.java`
  - Adds Thai notification titles for Step 2 actions.
- `backend/src/test/java/th/co/glr/hr/pricingrequest/PricingFactoryQuoteCostingIntegrationTest.java`
  - Covers the revised acceptance scenario end to end with real PostgreSQL/Testcontainers.

## Frontend Files

- `frontend/src/api/routes.js`
- `frontend/src/api/hrApi.js`
- `frontend/src/api/queryKeys.js`
- `frontend/src/api/mockApi.js`
- `frontend/src/features/pricingRequests/pricingRequestMeta.js`
- `frontend/src/features/pricingRequests/pricingRequestMeta.test.js`
- `frontend/src/utils/format.js`

These add API access and mock behavior, but not a full Import quote/costing workspace UI.

## Verification Completed

- `./mvnw -B -DskipTests compile` passed.
- `./mvnw -B -Dtest=FlywayMigrationTest test` passed with escalated Testcontainers access.
- `./mvnw -B -Dtest='PricingRequest*Test' test` passed: 160 tests.
- `./mvnw -B clean -Dtest=PricingFactoryQuoteCostingIntegrationTest test` passed.
- `./mvnw -q -Dtest='PricingRequest*Test,PricingFactoryQuoteCostingIntegrationTest,FlywayMigrationTest' test` passed with escalated Testcontainers access. Final surefire summaries: 163 tests, 0 failures, 0 errors, 0 skipped across the targeted slice.
- `npm test -- --run src/api/contract.test.js src/features/pricingRequests/pricingRequestMeta.test.js` passed: 29 tests.
- `npm run lint` passed with 0 errors and 3 pre-existing warnings in `CommissionPage.jsx` and `PayrollPage.jsx`.

## Notes and Gaps

- The Step 2 backend/API foundation is implemented. A complete first-class Import UI for factory quote negotiation and costing has not been built yet.
- Catalog base price snapshot columns exist, but the PR item DTO/UI has not yet been extended to present catalog base price snapshots to Sales.
- Factory response attachments are represented only as notes/structured item fields in this pass; no document upload surface was added.
- The existing Step 1 `request information` action remains assigned-Import-only. Step 2 factory quote and costing write actions are Import department-wide as required by the amendment.

## Suggested Next Prompt

Implement the Import UI for Step 2 using the new factory quote and costing APIs. The UI should let Import generate/send factory requests, record revised responses, mark revisions ready for costing, create/recalculate costing drafts, and explicitly submit to CEO while Sales only sees catalog base price and progress.
