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
  summary cards as the ONLY filter row (+เสียงาน bucket; see follow-up below — the status
  chips were later removed as redundant), columns ดีล/โครงการ · ผู้ดูแล · สถานะดีล (stage
  badge + operational sublabel) · progress bar · days-stale; mobile card.
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
2. Dashboard queue cards show counts but land on the UNFILTERED list (status filtering was
   dropped entirely in the follow-up below) — import/CEO locate work via the stage sublabel
   or search.
3. TicketDetailPage is large; DealStagePanel is a separate component but the page itself
   remains a refactor candidate (pre-existing).

## Next prompt
Both branches ready for PR: backend `3c41449` first, then this one. After merge, optional
follow-ups: demo-seed stages for the Render showcase, dashboard ภาพรวม gaining phase-funnel
cards, C1–C12/D1–D6 pipelines (schema room reserved via ticket_event kinds pattern).

---

## Follow-up (2026-07-17): status chips removed, phase cards colored

User feedback: the two filter rows were ซ้ำซ้อน. Changes:
- `TicketListPage.jsx` — operational STATUS_TABS chips row + `?status=` filtering REMOVED;
  the phase cards are THE filter. Each card now carries its phase color (dot + tinted
  active state); เสียงาน keeps danger. Refresh button moved to the header actions.
- `frontend/src/index.css` — new `--color-phase-1..5` (+`-bg`) design tokens, adopted from
  the Claude Design prototype's PHASE_TONE/hexTint (per the PR #221 tokenization direction).
- `DealStageStepper.jsx` — PhaseTracker + StageProgressBar segments now fill with their
  phase color (static class maps; Tailwind needs literal class names).
- `TicketDashboard.jsx` — queue cards still show counts but land on the unfiltered
  งานขาย list (user chose to drop status filtering entirely).
- Row-level operational sublabel under the stage badge KEPT (user decision) — that is now
  the only list-level view of where the paperwork stands.
- Verified: lint 0 errors · 118/118 tests · build PASS · mock browser (chips gone, colored
  cards, เฟส 2 filter active state + per-phase bar segments).

---

## Follow-up (2026-07-17): issued documents stay reachable per stage

User: deposit/มัดจำ doc generation must live in the respective stages. Generation already did
(ออกใบแจ้งยอดมัดจำ at ORDER_RECEIVED once the PO is confirmed); the gap was AFTER issuance —
later stages had no doc access in the stage panel. `TicketDetailPage.jsx` docActions now adds:
- **ดูใบแจ้งยอดมัดจำ** whenever the payment track is past CUSTOMER_CONFIRMED (the notice
  exists) — opens the deposit page (view/download/Rev) at DEPOSIT_RECEIVED → CLOSED_PAID.
- **ใบเสนอราคา <number> (PDF)** download for the latest quotation whenever one exists,
  alongside the stage-gated generate/Rev button.
Verified in mock browser: ticket 12 (PROCUREMENT) shows Rev + QT-2026-0005 PDF + ดูใบแจ้งยอด
มัดจำ (navigates to /tickets/12/deposit); ticket 11 (ORDER_RECEIVED) shows the generate
button + QT-2026-0004 PDF. Lint 0 errors · 118/118 tests · build PASS.
