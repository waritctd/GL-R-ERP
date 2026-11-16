# Sales Redesign Gap Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Build every "after" in the owner's Sales Redesign Review that the 2026-11-16 audit found NOT BUILT or PARTIAL.

**Architecture:** One slice = one branch off `origin/develop` = one PR. Frontend-first; a backend change only where a drawing needs data the server does not send, stated explicitly in the PR.

**Tech Stack:** React + Vite + Tailwind 4 (frontend), Spring Boot + Postgres (backend).

**Spec:** `docs/superpowers/specs/2026-11-15-sales-flow-redesign-design.md`. Design authority for look and labels: the review artifact https://claude.ai/artifact/LJbt6Q1QzW2B2W9VhSjt4u (saved HTML: `C:\Users\user\.claude\projects\C--Users-user-Documents-GitHub-GL-R-ERP\21665dad-d5d9-4895-9692-9b2bd2afbd34\tool-results\artifact-9c52c656-1794690962-b51b.html`). Gap list: `docs/superpowers/plans/2026-11-16-sales-redesign-gap-audit.md`.

## Global Constraints

- Owner rule 2026-11-16: the owner sees the prototype of a slice BEFORE it is implemented. No slice after A is dispatched until the owner has looked at its drawing.
- Owner ruling 2026-11-16: sales screens say "งาน", not "ดีล" (labels only; identifiers, routes, API fields, document codes, printed documents unchanged).
- Test-first with a recorded red run (CLAUDE.md). Coverage list goes in the PR body.
- Any authorization change ships a real-Postgres integration test through the real service, written wrong-way-round, with a mutation check.
- Commission, payroll and tax math are untouchable.
- `hrApi.js` and `mockApi.js` stay mirrored (contract test).
- Tailwind-first; no new page CSS files; phone widths 320–375px without horizontal page scroll; tap targets at least 44px.
- At most 2 agents at once (16 GB RAM). One implementer at a time; never two Maven runs against the local test DB at once.
- Implementers commit locally only. The controller pushes and opens PRs. Nothing merges to `main` or deploys without the owner's go.
- Every slice gets an ADVERSARIAL review: a reviewer whose job is to invalidate the work (find where it differs from the drawing, break it with inputs, and attack the tests: tests that cannot fail, tests that pass with the change reverted, mocks standing in for behaviour).

## Review Focus

- A deal with no value, no designer, no expected quarter: the card and the totals must still render and add up.
- A manager or CEO opening the board with zero deals, and with hundreds: no crash, no horizontal page scroll.
- A role that must not see team data calling the team endpoint directly: refused, zero rows.
- A stored view preference that is corrupt or from another user: falls back to the default.
- Existing deep links (`/tickets?owner=`, `?status=`) keep working.

---

### Task 1: Slice A — `/tickets` becomes "งานทั้งหมด" (board + table toggle), menu rename, team board endpoint

Branch `feat/all-deals-board`, worktree `.claude/worktrees/all-deals-board`. Drawing: template `a-board`. Scope and rulings were given to the implementer directly (dispatch + two follow-up messages on 2026-11-16): reuse `DealBoard`; sales reads `myWork.board`; sales_manager/ceo read a new read-only team-board endpoint under the `teamOverview` gate with a real-DB authz test; table kept behind the "ตาราง" toggle; view remembered per user; menu entry "งานทั้งหมด · All deals"; single "แดชบอร์ด" entry kept; "งาน" wording on touched files.

### Task 2: Slice B — deal detail page redesign

Drawings: `a-deal`, `a-deal-quote`, `a-deal-money`, `a-deal-ship`. S-code stepper, "ขั้นต่อไป" card, header "โทรแล้ว" / "นัดเข้าพบ", five tabs "ภาพรวม · ใบเสนอราคา · เงิน · ส่งของ · ไฟล์", history merged into ภาพรวม, quiet links for พัก / ปิด / ยกเลิก replacing the red danger zone. BLOCKED until the owner has seen the prototype.

### Task 3: Slice C — deal dialogs

Drawings: `m-create`, `m-stage`, `m-hold`, `m-lost`, `m-cancel`, `m-deposit-issue`, `m-remaining`, `m-ir`, `m-delivery`, plus the missing "ตามครั้งถัดไป" / "ตามต่อ" chips in `m-call` and `m-visit-log`. Needs owner decisions first: the `m-cancel` reason list is a backend enum change; `m-hold`'s return date is a new stored field. BLOCKED until the owner has seen the prototype and answered those two.

### Task 4: Slice D — "นัดเข้าพบ" page, phone "วันนี้ของฉัน", grouped finance worklist, remaining menu entries

Drawings: `a-mywork` phone frame, `a-fin-land`, `NAV`. BLOCKED until the owner has seen the prototype.

### Task 5: Slice E — "ดีล" → "งาน" label sweep across the remaining sales screens

Labels only. Runs last so it does not collide with slices A–D.
