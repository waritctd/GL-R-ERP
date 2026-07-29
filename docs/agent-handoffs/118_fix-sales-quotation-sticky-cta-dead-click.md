# 118 — fix/sales-quotation-sticky-cta-dead-click

**Branch:** `fix/sales-quotation-sticky-cta-dead-click` (off `origin/main` @ `1b6db578`)
**Status:** implemented, reviewed, verified. Frontend-only.

## Why this is its own branch

The bug fixed here was **found and documented** by the PR #343 session, in
`117_refactor-ui-ticket-header-actions.md` → section "Follow-up: CI e2e fix — PR #343" →
subsection "A second, real bug found while making the specs actually pass". That session
deliberately did **not** fix it (out of its own scope) and flagged it for a follow-up.

PR #343 has since merged to main (merge commit `af991f31`, 2026-07-29), so this fix could not be
pushed onto that PR. It lands as its own branch off latest `origin/main` instead.

**The full technical write-up — root cause, the rejected Option 1, the decision tree, known risks,
and the sales-workflow behaviour-change callout — lives in the appended section of
`117_refactor-ui-ticket-header-actions.md`, not duplicated here.** Read that file's final section
first; this file is the branch-level checklist CLAUDE.md requires.

## 1. Files changed

- `frontend/src/features/tickets/DealQuotationPanel.jsx` — `issueQuotation` now takes the quotation
  id as its mutate variable instead of closing over `current.id`; added a `createAndIssueQuotation`
  chained mutation (create draft → issue it); rewrote `openIssueQuotation`'s ref opener as a
  5-branch decision tree (in-flight guard → issue existing draft → "query not settled, don't risk a
  duplicate draft" toast → create-and-issue → honest error toast); gave `openConfirmOrder` the same
  pending-guard + error-toast treatment; updated the in-panel `!current` copy and the component doc
  comment.
- `frontend/src/features/tickets/TicketDetailPage.test.jsx` — new describe block, 3 tests: the
  regression itself (create+issue chained, the *draft* id — not the PR id — flows into issue), the
  unchanged happy path (existing draft → issue it, no duplicate create), and defense-in-depth
  (ISSUED-only quotation → error toast, neither mutation fires).
- `docs/agent-handoffs/117_refactor-ui-ticket-header-actions.md` — appended the full write-up
  described above.
- `docs/agent-handoffs/118_fix-sales-quotation-sticky-cta-dead-click.md` — this file.

`frontend/src/features/tickets/salesActions.js` is **deliberately untouched** — see 117 for why
narrowing its ISSUE_QUOTATION bucket (the task's Option 1) was rejected.

## 2. Commands run

```
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
```

Plus a mutation-check (see below) and `git fetch` / rebase-equivalent (branched fresh off
`origin/main`, so linear by construction).

## 3. Tests / build results

- `npm run lint` — **0 errors**, 1 pre-existing `PayrollPage.jsx:336` warning (expected, unchanged).
- `npm test -- --run` — **72 files / 768 tests, all pass** (765 → 768; the 3 new tests).
- `npm run build` — **pass**, no new warnings.
- **Mutation-check** (repo rule: "a green test that cannot fail is not evidence"): reverting
  `openIssueQuotation` to its old silent no-op turned **exactly the 2 new behaviour tests red**
  (`2 failed | 766 passed`) and nothing else; reverted, diffstat identical afterwards.
- **Playwright e2e: NOT run this session.** Reasoned only: both `frontend/e2e/pcr-chain.spec.js`
  and `frontend/e2e/deposit-fulfilment-close.spec.js` click `deal-quotation-create` and then wait
  for the `พร้อมออกใบเสนอราคาแล้ว` hint before clicking the sticky `ticket-primary-action`, so they
  always hit the **unchanged** "editable draft exists" branch. Grepped and confirmed nothing in
  `e2e/` or `src/` other than `DealQuotationPanel.jsx` itself references the in-panel copy that
  changed. CI's `e2e` job is the real check.

## 4. Authz evidence

**No authorization change.** `canCreateCustomerQuotation`, `canManageCustomerQuotation`,
`canConfirmOrder`, `canViewCustomerQuotation` and `isCustomerQuotationEditable`
(`frontend/src/features/pricingRequests/pricingRequestMeta.js`) are untouched, and every branch of
the new decision tree still re-checks the same predicates before mutating. The change only alters
**which already-permitted mutations one already-visible button fires** — no role gate, no scope or
filter, no change to who may read or write whose rows. No backend file changed.

Consequently no real-DB integration test is required by CLAUDE.md's "Permission changes must ship
evidence" rule. Verification was **frontend Vitest/RTL only**, so anything permission-shaped that a
reader might infer from these tests is mock-level and **not authoritative** — per CLAUDE.md's mock
authz rule. Nothing here claims otherwise.

## 5. Known risks

Full list in 117's appended section. The headline items:

- **Intentional sales/CRM workflow behaviour change**, stated explicitly per CLAUDE.md's "Sales flow
  redesign" section: issuing a customer quotation used to take two deliberate clicks (create draft,
  optionally discount it, then issue). A rep clicking the sticky "ออกใบเสนอราคา" with no draft yet
  now creates **and** issues in one click, with default no-discount terms. Mitigations: the in-panel
  create-only button remains (its copy now says so explicitly), and `createRevision` from `ISSUED`
  makes an accidental issue recoverable rather than terminal.
- `createAndIssueQuotation` is not atomic: a create that succeeds followed by a failing issue leaves
  an un-issued DRAFT the rep didn't ask for (visible and actionable in the panel; no compensating
  rollback implemented).
- **Post-success refetch window** (found in the review pass, untested): during `invalidate()`'s
  async refetch, `isSuccess` is still true with stale-empty data, so a fast second click re-enters
  the create branch. Both the mock (`mockApi.js:6316`) and the real service
  (`CustomerQuotationService.java:117`, which also takes `lockPricingRequest`) gate creation on
  `APPROVED_FOR_QUOTATION`, and `issue` flips the PR to `QUOTATION_ISSUED` — so it 409s server-side.
  Residual defect is a confusing error toast, **not** data corruption. Clean fix (out of scope):
  disable the sticky button while a panel mutation is pending, which needs the panel to report
  pending state up to `TicketDetailPage` (the ref is currently one-way, parent → panel).
- Not verified in a live browser or against the real backend this session.

## 6. Exact next prompt for the next agent

> On branch `fix/sales-quotation-sticky-cta-dead-click` (or on `main` after it merges), close the
> residual double-click hole recorded in `docs/agent-handoffs/118_fix-sales-quotation-sticky-cta-dead-click.md`
> §5 "Post-success refetch window". `DealQuotationPanel`'s ref opener guards on
> `*.isPending`, but the sticky primary button in `TicketDetailPage.jsx` (`data-testid="ticket-primary-action"`,
> the `issue_quotation` / `confirm_order` branches around line 804-817) is never `disabled`, so a
> fast second click during the post-success `invalidateQueries` refetch re-enters the create branch
> and 409s server-side with a confusing error toast. Extend `DealQuotationPanel`'s
> `useImperativeHandle` to expose its pending state (e.g. `isBusy()`), have `TicketDetailPage` read
> it to set `disabled` on that button, and add a unit test that a double `fireEvent.click` fires
> `createCustomerQuotation` exactly once. Frontend-only, no authz change. Run
> `cd frontend && npm run lint && npm test -- --run && npm run build` and update this handoff.
