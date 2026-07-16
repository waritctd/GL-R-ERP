# Agent Handoff

## Task
Frontend half of the **deal sales pipeline** (see `65_feat-project-sales-pipeline-backend.md`):
ใบขอราคา and โครงการ merged into ONE งานขาย page — one ticket = one deal, the 14 stages are
what it takes to close that deal, and doc generation surfaces on exactly the stage it belongs
to. This REPLACES the earlier project-based UI (separate /projects pages) after the user
redirected; visual language still adapted from the user's Claude Design prototype (DesignSync
project `c9567ff6-4556-4f28-ac89-5cb84ef40e1e`, PipelineApp.dc.html), no ⚡ badge.

## Branch
`feat/project-sales-pipeline-ui` (branched from `main` at `64391f2`; history REWRITTEN
2026-07-17 — earlier project-based commits replaced; never pushed). Merge the backend branch
(`feat/project-sales-pipeline-backend` @3c41449) FIRST.

## Base Commit
`64391f2`

## Agent / Model Used
Claude Opus 4.8 (plan + implementation + browser verification)

## Files changed
- `frontend/src/features/tickets/stageMeta.js` (+test) — canonical 14-stage/5-phase metadata;
  gates mirror TicketService (owner = ticket.createdById; sales_manager passes sales stages;
  account money fallback; import PROCUREMENT fallback); `PROCUREMENT_SUBSTEPS` map
  fulfillment_status; auto stages = ORDER_RECEIVED/DEPOSIT_RECEIVED/PROCUREMENT/CLOSED_PAID.
- `frontend/src/features/tickets/DealStageStepper.jsx` — PhaseTracker (proportional 5-segment
  bar), phase accordion (current phase auto-expanded), StageProgressBar (list rows).
- `frontend/src/features/tickets/DealStagePanel.jsx` (NEW) — the deal-detail pipeline panel:
  tracker + current-stage hero (advance button only when next stage is manual+permitted;
  hint says who/what advances it otherwise), PROCUREMENT substep chips, lost banner + reopen,
  แก้ไขสถานะ modal, เสียงาน modal, and **เอกสารของขั้นนี้** — stage-gated doc actions the
  parent renders from its real `can` flags (quotation at quote stages, deposit notice at
  ORDER_RECEIVED w/ CUSTOMER_CONFIRMED, IR at deposit-ready, remaining invoice at
  GOODS_RECEIVED).
- `frontend/src/features/tickets/UpdateStageModal.jsx` (+test), `MarkLostModal.jsx` — deal
  props; backward-needs-note mirrored.
- `frontend/src/features/tickets/TicketListPage.jsx` (+test) — THE one งานขาย page: phase
  summary cards as filters (+เสียงาน bucket), operational status chips kept (dashboard
  ?status= deep links intact, new "ดีลเริ่มต้น" draft chip), columns ดีล/โครงการ · ผู้ดูแล ·
  สถานะดีล (stage badge + operational sublabel) · progress bar · days-stale; mobile card.
- `frontend/src/features/tickets/TicketDetailPage.jsx` — DealStagePanel wired through the
  existing doAction/applyTicketUpdate machinery; EDITABLE_STATUSES += 'draft'; new can.submit
  + "ส่งขอราคา" button (only when items exist) + no-items hint; event labels for
  STAGE_CHANGED/MARKED_LOST/REOPENED.
- `frontend/src/features/tickets/TicketCreateModal.jsx` — "สร้างดีลใหม่": โครงการ required,
  **items optional** (lightweight lead-stage start; add via แก้ไขรายการสินค้า later).
- `frontend/src/features/sales/SalesTabs.jsx` — 2 tabs: ดีลทั้งหมด | ภาพรวม.
- `frontend/src/components/layout/AppShell.jsx` + `Sidebar.jsx` — one งานขาย item →
  /tickets, `match: ['/tickets','/ticket-overview']`.
- `frontend/src/App.jsx` — /projects routes removed.
- `frontend/src/api/routes.js` / `hrApi.js` — projects pipeline namespace REMOVED; tickets
  gains updateStage/markLost/reopen (POST /{id}/stage|lost|reopen).
- `frontend/src/api/mockApi.js` — **load-time stage backfill mirroring V50's SQL** (demo deals
  always land where the migration would put them); list/get expose
  salesStage/lostReason/lostAt/stageUpdatedAt (+projectId/Name on list); create mirrors
  400-without-project + draft-without-items(+CREATED event, no notify); submit 400s without
  items; editItems allows draft; updateStage/markLost/reopen with stageMeta gates;
  autoAdvanceStage in the 4 milestone transitions.
- `frontend/src/data/demoData.js` — tickets 11/12 linked to mock projects; NEW ticket 15 =
  lightweight lead-stage draft deal (PRESENTATION, no items).

## Commands run
- `cd frontend && npm run lint && npm test && npm run build`

## Tests / build results
- Lint 0 errors (4 pre-existing warnings) · vitest **27 files / 118 tests PASS** (incl.
  contract.test.js parity — hrApi↔mockApi both directions, no KNOWN_GAPS entry) · build PASS.
- frontend-mock browser verification (screenshots in session): merged งานขาย page (phase
  cards, stage badges, progress bars); lead deal PR-2026-0015 advance PRESENTATION→
  SPEC_APPROVED one-click; draft shows no-items hint and no submit until items exist;
  ticket 11 (ORDER_RECEIVED) shows ออกใบแจ้งยอดมัดจำ under เอกสารของขั้นนี้; ticket 12
  (PROCUREMENT) shows fulfillment substep chips; เสียงาน (PRICE) → red banner → เปิดดีลอีกครั้ง
  resumes at stage 11. **Mock authz is non-authoritative — permission rules are covered by
  TicketServiceTest on the backend branch.**

## Known risks
1. Impeccable hook flags in TicketListPage (#3b82f6/#ef4444 dot accents), TicketCreateModal,
   mockApi fixture HTML, styles.css side-tab are all pre-existing/documented — not restyled
   or suppressed in this diff.
2. The dashboard's queue cards still deep-link ?status= — kept working; drafts get their own
   chip but no dashboard card (lead deals are sales' private work).
3. TicketDetailPage is large; DealStagePanel is a separate component but the page itself
   remains a refactor candidate (pre-existing).

## Next prompt
Both branches ready for PR: backend `3c41449` first, then this one. After merge, optional
follow-ups: demo-seed stages for the Render showcase, dashboard ภาพรวม gaining phase-funnel
cards, C1–C12/D1–D6 pipelines (schema room reserved via ticket_event kinds pattern).
