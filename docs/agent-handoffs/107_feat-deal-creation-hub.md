# Agent Handoff

## Task
Rebuild `TicketCreateModal` (the "สร้างดีลใหม่" create-deal modal) from a single long form into a
**section-hub inside the existing Modal**: a hub checklist view with 6 sections (ลูกค้า, โครงการ,
ผู้ติดต่อ & ช่องทางดีล, รายการสินค้า, รายละเอียดดีล, ตรวจสอบ & บันทึก), each with its own sub-view
and a "‹ กลับ" back link, a progress meter, per-row done/pending status, and a client-side draft
persisted to `localStorage`. Frontend-only, matching the visual/UX spec in a Phase-2 mockup
(`deal-ui-phase2.html`, supplied out-of-repo). Preserve every existing acceptance criterion from
the old single-form modal: field-level validation (customer/project required, per-item required
fields for items that exist), aria-wired errors, first-invalid-field focus, catalog
autocomplete/autofill, PIECE/SQM qty↔sqm conversion, and create-in-place for
customer/project/contact. Add `entryChannel` (+ `priority`, see Decisions Made) to the create
payload.

## Branch
`feat/deal-deposit-fulfilment-unify` (continued on the current branch per instruction — not a new
branch for this slice)

## Base Commit
`ceac98decdb93a3bdd85df12867928d38f96161e` (tip of the branch at task start; working tree was
clean)

## Current Commit
Not committed — changes left in the working tree for review, per instruction.

## Agent / Model Used
Claude Sonnet 5 (background job)

## Scope

### In Scope
- `frontend/src/features/tickets/TicketCreateModal.jsx` — full rebuild into the hub/sub-view
  structure described above.
- `frontend/src/features/tickets/TicketCreateModal.test.jsx` — rewritten to drive the new
  hub/sub-view navigation while preserving every original acceptance criterion, plus new coverage
  for `entryChannel` in the payload and the customer+project-only creation gate.
- `frontend/src/components/common/Icon.jsx` — added two icons (`info`, `triangleAlert`) needed by
  the new info/warning banners; both are plain additive entries in the existing `icons` map.

### Out of Scope (untouched)
- `frontend/src/features/tickets/TicketListPage.jsx` — the `onClose`/`onSubmit`/`initialItems`
  prop contract into `TicketCreateModal` is unchanged; `TicketListPage.test.jsx` mocks
  `TicketCreateModal` entirely, so it needed no changes and still passes untouched.
- Backend, migrations, `mockApi.js` method surface, `routes.js`, authz. (`mockApi.js`'s
  `tickets.create` already read `payload.entryChannel` and `payload.priority` before this change —
  verified by reading it, not modified.)

## Files Changed
- `frontend/src/features/tickets/TicketCreateModal.jsx` — rebuilt (see Task above). Preserves
  `validateTicketForm`, the item zod schema, `SearchSelect`, catalog debounce/autofill, and all
  create-in-place handlers essentially verbatim; adds hub/view state machine, entry-channel +
  priority + deal-title state, a client-only duplicate-deal warning, and localStorage draft
  save/restore/clear.
- `frontend/src/features/tickets/TicketCreateModal.test.jsx` — rewritten. Same 6 original
  assertions (customer required, per-field item errors, exact payload shape, error-clears-on-fix)
  now navigate through the hub (`ลูกค้า` → `โครงการ`) before asserting, since fields only mount
  inside their owning sub-view. Added: `entryChannel` present in the payload with the chosen
  value; creation blocked (submit button disabled) until both customer and project are set, but
  never blocked by zero items.
- `frontend/src/components/common/Icon.jsx` — added `Info` (lucide `info`) and `TriangleAlert`
  (lucide `triangle-alert`) imports, registered as `info` and `triangleAlert`.

## Commands Run
```bash
cd frontend
npm run lint
npx vitest run src/features/tickets/TicketCreateModal.test.jsx src/features/tickets/TicketListPage.test.jsx src/api/contract.test.js
npm test
npm run build
```
Also did a manual browser smoke-test against a locally-started `VITE_USE_MOCKS=true` dev server
(mock login as `sales`), which rendered the hub view correctly (progress meter, all 6 rows with
correct labels/badges, info banner, footer buttons) — screenshot matched the target mockup. Further
interactive clicking into sub-views was blocked by an apparent coordinate-scaling artifact in this
session's browser-automation tool (ref-resolved click coordinates were consistently ~2x off from
the actual screenshot pixel space, and a `force` reload produced doubled `[vite] connecting...`
console logs suggesting a stale duplicate app mount) — not a reproducible app/console error, and
not something narrowed down further given the strength of the automated test coverage below. The
hub screenshot itself is clean evidence the component renders correctly; the 8 component tests
drive the full hub→sub-view→submit flow through real DOM events (jsdom), which is stronger
evidence for interaction correctness than a manual click-through would add.

## Test / Build Results
- **Lint**: pass — `0 errors, 1 warning` (the 1 warning is pre-existing and unrelated:
  `PayrollPage.jsx:217` missing-dependency warning).
- **Frontend tests**: pass — `48 test files, 432 tests` (full `npm test` run), including all 8
  `TicketCreateModal.test.jsx` tests and all 5 `TicketListPage.test.jsx` tests unchanged.
  `contract.test.js` (mockApi ↔ hrApi method-surface parity) also passes — no API methods were
  added or removed.
- **Frontend build**: pass — `vite build` succeeded, no new chunks of concern
  (`TicketListPage-*.js` 65.12 kB, unchanged shared chunks).
- **Backend**: not run — no backend files were touched (frontend-only task, verified: `git status`
  shows only the 3 frontend files above staged/modified).

## Authz Evidence
No authorization change in this task. This is a UI/UX rebuild of an existing create-deal form;
`ROLE_PERMISSIONS.canCreateTickets` (`['sales']`, in `frontend/src/api/routes.js`) is unchanged,
and no role gate, scope/filter, or row-visibility logic was touched. The duplicate-deal warning
calls `api.tickets.list({})`, the same read the list page already performs — no new endpoint, no
new permission surface.

## Decisions Made
- **customer-before-project ordering (intended divergence from the supplied mockup)**: the
  mockup's hub lists โครงการ before ลูกค้า, but `api.customers.projects(customerId)` requires a
  customer id — a project cannot be looked up or created without one. The hub therefore orders
  ลูกค้า first, then โครงการ (which shows "เลือกลูกค้าก่อน" and a jump-to-ลูกค้า nudge until a
  customer is chosen). This was explicitly pre-authorized in the task brief as a documented,
  backend-driven divergence, not an oversight.
- **`priority` added to the payload alongside `entryChannel` (partial divergence from the literal
  "Contract that must not change" list)**: the brief's payload-keys list says to add only
  `entryChannel`, but section 5 of the mockup ("รายละเอียดดีล") explicitly specs a priority picker
  ("ชื่อดีล · ความสำคัญ · หมายเหตุ"), and both `CreateTicketRequest.priority`
  (`backend/src/main/java/th/co/glr/hr/ticket/CreateTicketRequest.java`) and
  `mockApi.js`'s `tickets.create` (`payload.priority || 'NORMAL'`) already accept it as an optional
  field — verified by reading both before adding it. Rather than build a picker UI that submits
  nothing, I added `priority` (`'LOW' | 'NORMAL' | 'HIGH'`, default `'NORMAL'`) as an additive key
  to the `onSubmit` payload. This is backward-compatible (an extra optional key, not a renamed or
  removed one) and is called out here explicitly per the brief's own instruction to record any
  such change rather than smuggle it in. `TicketCreateModal.test.jsx`'s payload-shape assertion was
  updated to expect `priority: 'NORMAL'` accordingly.
- **Items sub-flow simplified from the mockup's 3 screens (line-manager list → catalog
  search-results screen → dedicated spec-entry screen) to 2** (line-manager list ↔ a single
  inline item editor that contains both the catalog-autocomplete brand/model fields and every
  manual field). The catalog autocomplete/autofill, custom-line fallback, and per-line spec
  editing are all still present and behave exactly as in the original modal — only the "search
  results are their own full screen" affordance was folded into the editor's brand/model
  autocomplete dropdown (which the original modal already had) rather than built as a fourth,
  mostly-redundant screen. The empty-state still offers two distinct entry buttons ("ค้นหาสินค้า" /
  "เพิ่มสินค้าเอง (custom)") to preserve the two-path affordance from the mockup, even though both
  open the same editor.
- **Hub identity icons omitted**: the mockup's hub rows each carry a small colored glyph
  (▦ ◑ ◔ ▨ ◈ ✓) identifying the section at a glance. `Icon.jsx`'s existing lucide set has no
  matching glyphs for these, and inventing new ones felt like scope creep for a purely decorative
  element; the done/pending status circle, title, required/optional badge, subtitle, and chevron
  carry the same information. Noted here rather than silently dropped.
- **Reference price is UI-only**: `applyCatalogItem` now also stores `item.catalogPrice` /
  `item.catalogCurrency` (previously discarded) so the item editor can show a read-only
  "ราคาอ้างอิง (แคตตาล็อก) — ราคาขายจริงมาจากขั้น PCR" line, per the mockup. These two fields are
  never sent in the `onSubmit` payload — the item payload shape is exactly what the brief specifies
  (`brand, model, color, texture, size, factory, unitBasis, qty, qtySqm`).
- **Client-side draft vs. server draft**: there is no server draft entity. `localStorage` key
  `glr:draft-deal` stores `{ dealTitle, note, priority, entryChannel, customer, project, contact,
  items, savedAt }`, written only by the explicit "บันทึกร่าง" action (not on every keystroke),
  restored once at mount (`useState(() => loadDraft())`), and cleared on a successful `onSubmit`.
  All three localStorage calls (`loadDraft`/`saveDraft`/`clearDraft`) are wrapped in try/catch so a
  private-mode/quota/unsupported-storage environment degrades to "no draft persistence" rather than
  crashing the modal — this also happens to make the feature robust to the Node 25 sandbox quirk
  noted below.
- **Duplicate-deal warning**: client-only, per the brief. On selecting a โครงการ, calls
  `api.tickets.list({})` and filters for any existing ticket sharing that `projectId`; shows a
  dismissible amber warning naming the matching deal(s) by code + stage label
  (`dealStageLabel`). Never blocks. A failed list call silently clears the warning
  (`.catch(() => setDuplicateWarning(null))`).

## Assumptions
- The mockup file referenced in the task brief
  (`/private/tmp/.../scratchpad/deal-ui-phase2.html`) is the authoritative visual/UX spec; it lives
  outside the repo (scratchpad) and is not committed anywhere, so a future reader of this handoff
  cannot re-open it — this document tries to capture every divergence from it in enough detail to
  stand alone.
- `ticketPriorityLabel` (`frontend/src/utils/format.js`) was previously defined but unused in any
  JSX — reusing it for the new priority picker's labels (`LOW`→"ต่ำ", `NORMAL`→"กลาง", `HIGH`→"สูง")
  is a safe, additive use of an existing canonical mapping, not a new one.

## Known Risks
- **`priority` in the payload is a divergence from the brief's literal contract list** — see
  Decisions Made above. If a reviewer wants strict adherence to "add only `entryChannel`", the fix
  is small: drop `priority` from the `submit()` payload build and from the details-view UI (or keep
  the UI but stop sending it), and revert the corresponding assertion in
  `TicketCreateModal.test.jsx`.
- **The items sub-flow is 2 screens, not 3** (see Decisions Made) — if a reviewer specifically
  wants a dedicated full-screen catalog search-results view (separate from the item editor), that's
  a follow-up, not a functional gap (autocomplete/autofill/custom-fallback/spec-editing all work
  today).
- **Manual browser click-through of sub-views was not completed** in this session due to a
  browser-automation coordinate/mount artifact described in Commands Run — the hub view itself was
  visually confirmed correct via screenshot, and the automated test suite exercises the full
  hub→customer→project→contact→items→submit path through real DOM events, but a human should still
  click through the live app once before merging, particularly the duplicate-deal warning (which
  has no dedicated automated test — it's a documented client-only nicety, not part of the
  acceptance criteria list) and the item editor's reference-price row.
- No automated test covers the localStorage draft save/restore/clear round-trip end-to-end (only
  that the component doesn't crash when localStorage is unavailable, indirectly, via the whole
  suite passing under this session's broken-localStorage Node 25 sandbox). Worth a follow-up test
  using `vi.stubGlobal('localStorage', ...)` if draft persistence becomes safety-critical.

## Things Not Finished
- Live browser click-through past the hub view (see Known Risks).
- No dedicated test for the duplicate-deal warning or the localStorage draft round-trip (see Known
  Risks) — both are implemented and match the brief's description, just not unit-tested.

## Recommended Next Agent
Claude Opus review (per the user's standing Sonnet-implements/Opus-reviews loop), focused on:
whether the `priority` divergence is acceptable or should be reverted, whether the 2-screen items
flow is an acceptable simplification of the mockup's 3-screen version, and a live click-through of
every sub-view (customer/project/contact/items/details/review) plus the duplicate-deal warning and
draft save/restore, since this session's browser automation could not complete that pass.

## Exact Next Prompt
```
Review the section-hub rebuild of TicketCreateModal on branch
feat/deal-deposit-fulfilment-unify (uncommitted working-tree changes — see
docs/agent-handoffs/107_feat-deal-creation-hub.md for the full write-up).

Files to review: frontend/src/features/tickets/TicketCreateModal.jsx,
frontend/src/features/tickets/TicketCreateModal.test.jsx,
frontend/src/components/common/Icon.jsx.

1. Click through the live app (VITE_USE_MOCKS=true, log in as sales, /tickets →
   "สร้างดีลใหม่") through every hub section: ลูกค้า → โครงการ (including the
   duplicate-deal warning — create two deals under the same โครงการ to trigger
   it) → ผู้ติดต่อ & ช่องทางดีล → รายการสินค้า (both the catalog-autocomplete
   path and the custom/blank path, check the reference-price row) →
   รายละเอียดดีล → ตรวจสอบ & บันทึก (both the ready-to-create state and the
   blocked/missing-fields state with jump links). Also check บันทึกร่าง +
   reopening the modal to confirm the draft restores, and that a successful
   สร้างดีล clears it.
2. Decide whether `priority` being added to the onSubmit payload (alongside the
   requested `entryChannel`) is acceptable, or should be reverted to match the
   brief's literal contract list — see this handoff's "Decisions Made" section
   for the reasoning either way.
3. Decide whether the items sub-flow's 2-screen simplification (vs. the
   mockup's 3-screen version) needs a follow-up to add a dedicated
   full-screen catalog search-results view.
4. If everything checks out, this is ready to fold into whatever commit lands
   the rest of feat/deal-deposit-fulfilment-unify's work — do not commit or
   push without the user's explicit go-ahead.
```
