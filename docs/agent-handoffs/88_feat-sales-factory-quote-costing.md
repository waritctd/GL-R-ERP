# Handoff: Sales Pricing Step 2 - Factory Quotes and Costing

Date: 2026-07-20
Branch: `feat/sales-factory-quote-costing`
Base before work: `ba9836f` (`feat/sales-pricing-request-foundation`, stacked on latest `origin/main` at the start of the task)
Primary migrations: `V61__factory_quote_costing_foundation.sql`, `V62__pricing_step2_review_hardening.sql`

## Scope Implemented

- Added the Step 2 backend foundation for Import-owned factory quote requests, factory response revisions, explicit ready-for-costing marking, costing drafts, recalculation, and explicit CEO submission.
- Preserved Step 1 deal/ticket behavior: no automatic deal stage/status changes and no mutation of legacy ticket item price fields during factory quote/costing work.
- Preserved confidentiality boundaries: Sales and Sales Manager cannot read raw factory quote/costing data; Import and CEO can read the raw quote/costing history.
- Added frontend route/API/query key coverage, mock API behavior, and a first-pass Step 2 detail workspace for Sales, Import, and CEO visibility.

## Business Rules Captured

- Pricing request items are grouped by their fixed `factory` value when Import generates factory email drafts.
- Import must explicitly send factory requests, record responses, start negotiation, and mark a quote revision ready for costing.
- A later factory response supersedes the previous current revision in the same quote chain.
- Costing drafts are created only after all active fixed factories have current responses marked `READY_FOR_COSTING`.
- Recalculate is explicit and may happen multiple times; it never submits automatically.
- A new factory revision marks open costings stale. Stale costings cannot be submitted until recalculated.
- Submit to CEO is explicit. Submitted costings are immutable.
- Submitted costing moves the pricing request to `READY_FOR_CEO_REVIEW` and notifies CEO.
- Cancelled/superseded pricing requests are not operationally mutable, and open Step 2 quote/costing children are cancelled with the parent request.
- Import department users can request and resume Sales information loops from active Step 2 statuses without relying on a single assigned Import user.

## Backend Files

- `backend/src/main/resources/db/migration/V61__factory_quote_costing_foundation.sql`
  - Adds `sales.factory_quote`, `sales.factory_quote_item`, `sales.pricing_costing`, and `sales.pricing_costing_item`.
  - Adds quote/costing sequences, quote revision constraints, current-quote uniqueness, open-draft uniqueness, and pricing request Step 2 statuses.
  - Adds catalog snapshot placeholders on `sales.pricing_request_item`.
- `backend/src/main/resources/db/migration/V62__pricing_step2_review_hardening.sql`
  - Adds database invariants for costing idempotency, costing item uniqueness, current factory quote uniqueness, and factory quote client request replay protection.
- `backend/src/main/java/th/co/glr/hr/factoryquote/*`
  - Adds controller, service, repository, DTO/request records, and status enum for factory quote lifecycle.
  - Hardens send idempotency, response revisions after CEO submission, factory grouping from catalog-resolved factory snapshots, and ready-for-costing item completeness checks.
- `backend/src/main/java/th/co/glr/hr/pricingcosting/*`
  - Adds controller, service, repository, DTO/request records, and status enum for costing lifecycle.
  - Hardens draft idempotency, stale/open draft handling, explicit recalculation, required factory configuration, and BOT FX validation for non-THB currencies.
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestRepository.java`
  - Snapshots active catalog base price/factory data before submission and preserves Sales-visible catalog price context.
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
- `frontend/src/features/pricingRequests/PricingRequestDetailPage.jsx`
- `frontend/src/App.jsx`
- `frontend/src/app/permissions.js`

These add API access, mock behavior, queue-to-detail links, permissions, and the first usable detail workspace. Sales sees catalog base price/progress and information-response actions; Import can generate/send factory requests, record revisions, mark ready, create/recalculate costings, request information, and submit to CEO; CEO has read-only review visibility.

## Verification Completed

- `./mvnw -B -DskipTests compile` passed.
- `./mvnw -B -Dtest=FlywayMigrationTest test` passed with escalated Testcontainers access.
- `./mvnw -B -Dtest='PricingRequest*Test' test` passed: 160 tests.
- `./mvnw -B clean -Dtest=PricingFactoryQuoteCostingIntegrationTest test` passed.
- `./mvnw -q -Dtest='PricingRequest*Test,PricingFactoryQuoteCostingIntegrationTest,FlywayMigrationTest' test` passed with escalated Testcontainers access. Final surefire summaries: 163 tests, 0 failures, 0 errors, 0 skipped across the targeted slice.
- `npm test -- --run src/api/contract.test.js src/features/pricingRequests/pricingRequestMeta.test.js` passed: 30 tests.
- `npm run lint` passed with 0 errors and 3 pre-existing warnings in `CommissionPage.jsx` and `PayrollPage.jsx`.
- `npm run build` passed.
- `git diff --check` passed.

## Notes and Gaps

- The Step 2 detail workspace is functional but deliberately compact; it is not yet a polished production workflow with file upload controls or rich per-revision attachment management.
- Catalog base price/factory snapshots are now populated for selected catalog products, but legacy/free-text request items remain allowed for compatibility.
- Factory response attachments are still represented only as notes/structured item fields in this pass; no document upload surface was added.
- A full customer-change pricing request revision endpoint/workflow has not yet been added.

## Suggested Next Prompt

Implement the remaining Step 2 polish: attachment upload/history, a dedicated customer-change revision workflow, and richer Import/CEO review ergonomics on top of the new detail workspace.
