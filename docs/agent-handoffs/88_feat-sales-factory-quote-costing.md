# Handoff: Sales Pricing Step 2 - Factory Quotes and Costing

Date: 2026-07-20
Branch: `feat/sales-factory-quote-costing`
Base before work: `ba9836f` (`feat/sales-pricing-request-foundation`, stacked on latest `origin/main` at the start of the task)
Primary migrations: `V61__factory_quote_costing_foundation.sql`, `V62__pricing_step2_review_hardening.sql`, `V63__pricing_step2_units_and_notifications.sql`, `V64__pricing_step2_dispatch_and_attachments.sql`

## Scope Implemented

- Added the Step 2 backend foundation for Import-owned factory quote requests, factory response revisions, explicit ready-for-costing marking, costing drafts, recalculation, and explicit CEO submission.
- Preserved Step 1 deal/ticket behavior: no automatic deal stage/status changes and no mutation of legacy ticket item price fields during factory quote/costing work.
- Preserved confidentiality boundaries: Sales and Sales Manager cannot read raw factory quote/costing data; Import and CEO can read the raw quote/costing history.
- Added frontend route/API/query key coverage, mock API behavior, and a first-pass Step 2 detail workspace for Sales, Import, and CEO visibility.
- Added review follow-ups for catalog product selection, editable factory email drafts, explicit confirmation dialogs, department-wide Import labels, pricing-request notification links, CEO visibility, direct manual factory responses, canonical quote/costing unit handling, costing idempotency replay protection, idempotent factory email dispatch, raw quote attachments, and customer-change revisions.

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
- Factory email sends require an explicit `clientRequestId`; retrying the same request returns the already-sent quote without sending duplicate email, and dispatch attempts are audited in `sales.factory_quote_email_dispatch`.
- Import can upload optional raw factory quote attachments. Import and CEO can review/download them; Sales remains limited to catalog base price and pricing progress.
- Sales can create a customer-change revision from an active submitted pricing request. The prior request becomes `SUPERSEDED`, open Step 2 children are cancelled, and the new revision is created as a fresh `DRAFT`.

## Backend Files

- `backend/src/main/resources/db/migration/V61__factory_quote_costing_foundation.sql`
  - Adds `sales.factory_quote`, `sales.factory_quote_item`, `sales.pricing_costing`, and `sales.pricing_costing_item`.
  - Adds quote/costing sequences, quote revision constraints, current-quote uniqueness, open-draft uniqueness, and pricing request Step 2 statuses.
  - Adds catalog snapshot placeholders on `sales.pricing_request_item`.
- `backend/src/main/resources/db/migration/V62__pricing_step2_review_hardening.sql`
  - Adds database invariants for costing idempotency, costing item uniqueness, current factory quote uniqueness, and factory quote client request replay protection.
- `backend/src/main/resources/db/migration/V63__pricing_step2_units_and_notifications.sql`
  - Normalizes quote/costing item units to canonical `PER_SQM`, `PER_PIECE`, `PER_BOX`, and `PER_LINEAR_M` values before adding database constraints.
- `backend/src/main/resources/db/migration/V64__pricing_step2_dispatch_and_attachments.sql`
  - Adds factory quote email dispatch audit/idempotency storage and factory quote attachment indexing.
- `backend/src/main/java/th/co/glr/hr/factoryquote/*`
  - Adds controller, service, repository, DTO/request records, and status enum for factory quote lifecycle.
  - Hardens send auditing/notifications, direct manual responses from Import review, response revisions after CEO submission, factory grouping from catalog-resolved factory snapshots, canonical response-unit validation, ready-for-costing item completeness checks, idempotent dispatch, and optional attachment upload/download.
- `backend/src/main/java/th/co/glr/hr/pricingcosting/*`
  - Adds controller, service, repository, DTO/request records, and status enum for costing lifecycle.
  - Hardens draft idempotency replay checks, stale/open draft handling, explicit recalculation, canonical unit conversion, required factory configuration, and BOT FX validation for non-THB currencies.
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestRepository.java`
  - Snapshots active catalog base price/factory data before submission, preserves Sales-visible catalog price context, and creates customer-change revision records.
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestStatus.java`
  - Adds Step 2 statuses/transitions.
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestEventKind.java`
  - Adds Step 2 event kinds.
- `backend/src/main/java/th/co/glr/hr/notification/NotificationRepository.java`
  - Adds Thai notification titles for Step 2 actions and pricing-request links for Import/CEO notifications.
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

The create/edit modal now includes a catalog price picker so Sales can submit catalog-backed product lines with base-price snapshots instead of relying only on free-text descriptions.

The detail workspace now also exposes editable factory email drafts before send, client-request guarded send confirmation, raw factory quote attachment upload/download for Import/CEO, CEO raw quote/costing review, and Sales-owned customer-change revision creation.

## Decision Matrix Status

- Parent Pricing Request guards: Pass
- Cancellation child cascade: Pass
- Department-wide Import auth: Pass
- Information resume status: Pass
- Factory/config strictness: Pass
- BOT FX validation: Pass
- Costing Version 2 path: Pass
- Database uniqueness improvements: Pass
- Catalog selection and base price: Pass
- Direct manual response flow: Pass
- Multi-factory send audit/status: Pass
- Exactly-once email sending: Pass via `clientRequestId` plus dispatch-row claim/audit guard
- Costing replay parent validation: Pass
- Unit conversion integrity: Pass
- CEO notification matrix: Pass
- Factory email editing UI: Pass
- CEO raw quote review UI: Pass
- Customer-change revisions: Pass
- Optional attachments: Pass
- Complete automated verification: Pass for targeted Step 2 backend/frontend slices listed below
- Merge readiness: Ready after reviewer re-run
- Step 2 completion: Complete for the revised acceptance scenario

## Verification Completed

- `./mvnw -B -DskipTests compile` passed.
- `./mvnw -B -Dtest=FlywayMigrationTest test` passed with escalated Testcontainers access.
- `./mvnw -B -Dtest='PricingRequest*Test' test` passed: 160 tests.
- `./mvnw -B clean -Dtest=PricingFactoryQuoteCostingIntegrationTest test` passed.
- `./mvnw -q -Dtest='PricingRequest*Test,PricingFactoryQuoteCostingIntegrationTest,FlywayMigrationTest' test` passed. Final surefire summaries: 169 tests, 0 failures, 0 errors, 0 skipped across the targeted slice.
- `npm test -- --run src/api/contract.test.js src/features/pricingRequests/pricingRequestMeta.test.js src/features/pricingRequests/PricingRequestCreateModal.test.jsx` passed: 42 tests.
- `npm run lint` passed with 0 errors and 3 pre-existing warnings in `CommissionPage.jsx` and `PayrollPage.jsx`.
- `npm run build` passed.
- `git diff --check` passed.

## Notes and Gaps

- The Step 2 detail workspace is functional but deliberately compact; the core Import/CEO/Sales controls are present, but there is room for later UX refinement.
- Catalog base price/factory snapshots are now populated for selected catalog products, but legacy/free-text request items remain allowed for compatibility.
- Factory email dispatch now has dispatch-row idempotency/audit protection for retries. It is not a background transactional outbox worker.

## Suggested Next Prompt

Polish the Step 2 workspace UX: denser per-factory history, richer attachment previews, and guided CEO review affordances on top of the completed workflow.
