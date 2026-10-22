# Quotation ผู้ติดต่อ picker + one-page deal form — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The quotation gets an optional ผู้ติดต่อ picker over the customer's contacts (add-first dropdown, modal A, inline phone/email edit) whose name prints as ผู้สั่งซื้อ; the deal creation form drops ผู้ติดต่อ and becomes a one-page accordion with "+ เพิ่ม…" first in every dropdown.

**Architecture:** Two independently shippable parts on two branches/PRs. **Part A (`feat/quotation-contact-picker`)**: backend `resolveContact` stops inheriting the deal's contact and the renderer's signature slot reads the picked contact; the shared `QuotationContactPicker` grows the add-first dropdown and modal A and replaces the quotation editor's free-text ผู้ติดต่อ box; mock mirrors. **Part B (`feat/deal-form-onepage`)**: `TicketCreateModal`'s hub-and-sub-views become one accordion without the contact block; `SearchSelect`'s create-new row moves to the top; the deal page drops its ผู้ติดต่อ row. No schema change, no migration.

**Tech Stack:** React 18 / Vite / vitest + Testing Library (jsdom, no jest-dom); Spring Boot + JDBC + `AbstractPostgresIntegrationTest` (local Postgres via `TEST_DB_URL`); Playwright e2e-real (not extended).

**Spec:** `.design/quotation-contact/DESIGN.md` (owner rulings 2026-10-18, incl. the struck pre-fill and the confirmed signature-name rule).

## Global Constraints

- Part A branches off develop `e5603b2a`; Part B branches off develop after Part A merges (or off A if A is still open — then it is a stack and merges bottom-up with a merge commit).
- No commit/push/merge without Ploy's explicit say-so; "Commit (on say-so)" steps are skipped until then.
- Test-first, red seen before green, coverage list in chat per task; **revise existing tests, delete stale ones, add new only for distinct behaviour** (Ploy 2026-10-18). Targeted runs only: `cd frontend && npx vitest run <files>`; `cd backend && TEST_DB_URL=jdbc:postgresql://localhost:5432/glr_verify_it TEST_DB_USERNAME=ploy_warit TEST_DB_PASSWORD= ./mvnw -B -q -Dtest=<Class> -Dtest.fork.count=1 -Djacoco.skip=true -Dsurefire.failIfNoSpecifiedTests=false test`.
- **PDF byte-identical for the customer phone/email line** (ruling 2): `printedPhone/printedEmail` and the B6 line are not touched; only the editor's labels change.
- **Nothing is required** anywhere: no new 400s, no checklist blockers, no gates.
- Copy, verbatim: `ผู้ติดต่อ`; the ONLY hint on these screens: `ชื่อจะพิมพ์เป็นผู้สั่งซื้อบนใบเสนอราคา`; `เพิ่มผู้ติดต่อใหม่`; `ใช้ชื่อเดียวกับลูกค้า`; `เพิ่มและเลือกคนนี้`; `ยกเลิก`; `โทรศัพท์ลูกค้า`; `อีเมลลูกค้า`; `ไม่เติม "คุณ" หน้าชื่อผู้ติดต่อ`; deal form: `เสนอแก่`, `ดีลนี้เสนอแก่ใคร`, `สร้างดีล`, `เพิ่มลูกค้าใหม่`, `เพิ่มโครงการใหม่`. No other explanatory text on these screens.
- Tailwind tokens only (`frontend/src/index.css`); no new CSS files. Sales API contract changes stated in each PR body (A: `orderedByName` no longer written by the editor; `contactId` sent again; B: `CreateTicketRequest.contactId` no longer sent by the form — it stays nullable server-side).
- Part B: run the `information-architecture` skill before restructuring the form (CLAUDE.md).

## Review Focus

1. A **DRAFT that already has a typed signature name and no picked contact**, saved once after the change: the signature slot must print nothing (ruling 5), and the typed name is gone from the row — Task 2 + Task 4.
2. An **issued/approved row with a typed name and no contact** must keep printing that name after the change (frozen documents) — Task 2.
3. **Editing a picked contact's phone/email inline** must update only DRAFT quotations' snapshots; an issued quotation on the same deal must not change — Task 3 (frontend) relies on existing `refreshDraftContactSnapshot`; pin it in Task 1.
4. **Opening the editor on a quotation whose user cannot read the deal / deal has no customer**: the picker must render disabled, never crash or 500 — Task 4.
5. **Submit with a missing required section on the one-page deal form**: the failed-field jump must open the right accordion section and focus the field — Task 8.
6. **A revision of a quotation with a picked contact** keeps the parent's contact (no pre-fill elsewhere, but copy on revision stays) — Task 1.

---

## Part A — `feat/quotation-contact-picker`

### Task 1: `resolveContact` stops inheriting the deal's contact; revision copy and DRAFT refresh pinned

**Files:**
- Modify: `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationService.java:2894-2909` (`resolveContact`), Javadoc `:2880-2893`
- Test: `backend/src/test/java/th/co/glr/hr/dealquotation/DealQuotationIntegrationTest.java` (contact block `:2620-2740`, `updateContact_*` refresh tests `:3855-3974`)

**Interfaces:**
- Produces: `resolveContact(Long requestedContactId, Long existingContactId, TicketSummaryDto ticket)` precedence = requested → existing → **none** (blank snapshot). A given/inherited id must still exist and belong to `ticket.customerId()` else 400 `กรุณาระบุผู้สั่งซื้อ` (unchanged).

- [ ] **Step 1: Revise the failing tests.** Find the case(s) in the `:2620-2740` block that assert a quotation created with `contactId = null` on a deal WITH a `contact_id` inherits the deal's contact (grep `ticket.contactId\|inherits\|deal's contact`). Revise each to assert the snapshot is blank (`contactId()`, `contactName()`, `contactPhone()`, `contactEmail()` all null) — rename to `create_withNoContactId_doesNotInheritTheDealsContact`. Keep `create_withExplicitContact_usesIt_andRefusesAnotherCustomersContact`, `update_keepsTheDraftsContactWhenOmitted_andSwitchesOnAnExplicitId`, `createRevision_copiesTheParentsContactSnapshotVerbatim` (Review Focus 6) and the `updateContact_*` refresh tests (Review Focus 3) unchanged — they are the pins.
- [ ] **Step 2: Run RED**: `-Dtest=DealQuotationIntegrationTest#create_withNoContactId_doesNotInheritTheDealsContact` → fails on `contactId()` being the deal's id.
- [ ] **Step 3: Implement**: in `resolveContact`, drop the `: ticket.contactId()` arm; rewrite the Javadoc to cite the 2026-10-18 ruling (deal form no longer collects a contact; no pre-fill).
- [ ] **Step 4: Run GREEN**: the whole `DealQuotationIntegrationTest` class.
- [ ] **Step 5: Commit (on say-so)**: `refactor(sales): a quotation no longer inherits the deal's contact — ผู้ติดต่อ is picked per quotation`

### Task 2: Signature slot prints the picked contact; DRAFT without one prints nothing; issued rows frozen

> **Executed under a controller ruling (ledger, 2026-10-18):** the renderer stays untouched; `ordered_by_name` is server-derived from the resolved contact on every DRAFT write and `UpsertDealQuotationRequest.orderedByName` is ignored. The steps below describe the original renderer-side plan and are superseded by that ruling.

**Files:**
- Modify: `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationRenderAdapter.java:1136-1147` (`orderedByName(DealQuotationDto)`) and its call at `:230`
- Test: `backend/src/test/java/th/co/glr/hr/dealquotation/DealQuotationRenderAdapterV3Test.java:1012-1105`, `DealQuotationIntegrationTest.java:2778` (`render_draft_neverAutoFillsContactNameInSlot4_evenThoughOneIsRecorded`)

**Interfaces:**
- Produces: `static String orderedByName(DealQuotationDto q)` → `q.contactName()` when non-blank; else when `"DRAFT".equals(q.docStatus())` → `null`; else `q.orderedByName()` trimmed or null. (`printedPhone/printedEmail` untouched.)

- [ ] **Step 1: Revise the tests** (same names where the behaviour survives, renamed where it flips):
  - `signatories_orderedBy_printsTheManualNameWhenSet` → `signatories_orderedBy_printsTheManualNameOnlyOnANonDraftRow`: fixture `docStatus="ISSUED"`, contactName null, orderedByName "คุณสมชาย" → prints it (Review Focus 2).
  - `signatories_orderedBy_staysNullWhenNoManualNameEvenWithContactAndCustomerPresent` → `signatories_orderedBy_printsThePickedContactName`: contactName "คุณสมชาย ใจดี", orderedByName "typed" → prints the contact name, not the typed one.
  - `orderedByName_directly_neverFallsBackToContactOrCustomerNameAnyMore` → `orderedByName_draftWithoutContact_isNullEvenIfATypedNameIsStored`: `docStatus="DRAFT"`, contactName null, orderedByName "typed" → null (Review Focus 1).
  - Keep `noContact_andNoOrderedByName_signatureSlotStaysNullForTheDottedPlaceholder`; delete `orderedByName_directly_printsTheManualNameTrimmed` if it duplicates the first case (fold the trim assertion into it).
  - `DealQuotationIntegrationTest.render_draft_neverAutoFillsContactNameInSlot4_evenThoughOneIsRecorded` → `render_draft_printsThePickedContactInSlot4`: assert the rendered XLS slot carries the contact name.
- [ ] **Step 2: Run RED**: `-Dtest=DealQuotationRenderAdapterV3Test` → the three revised cases fail on the current typed-name rule.
- [ ] **Step 3: Implement** `orderedByName` per the interface; update the Javadoc (`:1136-1143`) to the new rule and the ruling.
- [ ] **Step 4: Run GREEN**: `-Dtest='DealQuotationRenderAdapterV3Test,DealQuotationOrderedByContactRenderTest,DealQuotationIntegrationTest'` — the OrderedByContact render tests must pass **unchanged** (phone/email line byte-identical).
- [ ] **Step 5: Commit (on say-so)**: `feat(sales): ผู้สั่งซื้อ signature slot prints the picked ผู้ติดต่อ; a draft without one prints the dotted line`

### Task 3: Shared picker — add-first dropdown, search, phone·email rows, modal A

**Files:**
- Modify: `frontend/src/features/quotations/QuotationContactPicker.jsx` (select `:230-250`, new-contact inline form `:322-379`, edit-in-place `:112-201,258-294`, omit checkbox `:296-320`)
- Modify (consumer): `frontend/src/features/pricingRequests/PricingRequestCreateModal.jsx:1013-1023` (label/hint props only)
- Test: `frontend/src/features/quotations/QuotationContactPicker.test.jsx`, `frontend/src/features/pricingRequests/PricingRequestCreateModal.test.jsx` (the two ผู้สั่งซื้อ tests at ~`:369`, `:386`, `:550`)

**Interfaces:**
- Produces: `QuotationContactPicker` props unchanged in shape (`customerId, customerName, value, onChange, error, showToast, disabled, idPrefix, onResolve, omitContactHonorific, onChangeOmitContactHonorific`, forwarded ref with `flushPendingContactSave()`) plus `label = 'ผู้ติดต่อ'` and `hint = 'ชื่อจะพิมพ์เป็นผู้สั่งซื้อบนใบเสนอราคา'`. Rendering: a combobox-style control (button + popover list, `role="listbox"`) whose FIRST row is `+ เพิ่มผู้ติดต่อใหม่` (`data-testid="contact-add-first"`), then a search input (`aria-label="ค้นหาชื่อ เบอร์ หรืออีเมล"`, client-side filter over the loaded list), then one row per contact showing name and `phone · email` (missing → `ไม่มีเบอร์` / `ไม่มีอีเมล`, muted, no chips). Selected card: name + เบอร์/อีเมล with the existing inline edit; `เปลี่ยน` and `ล้าง` actions. Modal A (`role="dialog"`, title `เพิ่มผู้ติดต่อใหม่`, subtitle `ลูกค้า: <customerName>`): fields ชื่อ * / นามสกุล / เบอร์โทร / อีเมล, checkbox `ใช้ชื่อเดียวกับลูกค้า`, buttons `ยกเลิก` / `เพิ่มและเลือกคนนี้`; only ชื่อ required; **no position field**; calls `api.customers.createContact(customerId, { firstName, lastName|null, phone|null, email|null })` then `onChange(created)`.

- [ ] **Step 1: Revise the picker tests** (`QuotationContactPicker.test.jsx`): the `ใช้ชื่อเดียวกับลูกค้า` describe's `openNewContactForm` helper now clicks the first row `contact-add-first` (not the `__new__` option); assertions move to the dialog; add to that describe ONE case `the add row is the first row of the list and the form has no ตำแหน่ง field`. The `edit-in-place` and `ไม่เติม "คุณ"` describes stay (selectors updated only where the control changed). The `optional on the PCR form` case (from #1156) becomes `shows the one hint and no required marker`. Add ONE search case: typing `081` filters to the contact with that phone. Revise the PCR modal's two ผู้สั่งซื้อ tests to the new label `ผู้ติดต่อ` and to picking via the list.
- [ ] **Step 2: Run RED**: `npx vitest run src/features/quotations/QuotationContactPicker.test.jsx src/features/pricingRequests/PricingRequestCreateModal.test.jsx -t "first row|ตำแหน่ง|081|ผู้ติดต่อ"` → fail on missing `contact-add-first` / dialog.
- [ ] **Step 3: Implement** per the interface. Reuse `Modal` from `components/common/Modal.jsx` and `FormField`; keep `contactDisplayName` export; the PCR modal passes nothing new (defaults give it the new label and hint; drop its old hint prop if any).
- [ ] **Step 4: Run GREEN**: both test files; `npx eslint` on the two source files.
- [ ] **Step 5: Commit (on say-so)**: `feat(sales): ผู้ติดต่อ picker — add-first dropdown with search, phone·email rows, and the add-contact modal`

### Task 4: Quotation editor — picker replaces the free-text box; customer phone/email relabelled; "คุณ" checkbox back

**Files:**
- Modify: `frontend/src/features/quotations/QuotationEditorPage.jsx` (`ORDERED_BY_FIELD_LABEL` `:202`, inputs `:2496-2512` and `:2645-2657`, payload `:1309-1320` `contactId`, `:1356-1364` `omitContactHonorific`, `:1368` `orderedByName`), `frontend/src/features/quotations/QuotationDealFields.jsx:47,77,87` (labels + hint)
- Test: `frontend/src/features/quotations/quotationOrderedByContact.test.jsx` (`:21-22` premise, `:240`, `:283`), `frontend/src/features/quotations/QuotationEditorPage.test.jsx` (`:773`, `:798`)

**Interfaces:**
- Consumes: Task 3's picker. Customer id for the picker = `ticket.customerId` from the editor's loaded deal (`ticket` state); when absent the picker renders disabled (`เลือกลูกค้าก่อน`) — Review Focus 4.
- Produces: upsert payload sends `contactId: contact?.id ?? null` and no longer sends `orderedByName` at all (the server derives it from the contact — Task 2 ruling), `omitContactHonorific` from state (checkbox under the picker, default false, seeded from the loaded quotation). Both inline and card placements render the picker where the text box was. `OrderedByContactFields` labels: `โทรศัพท์ลูกค้า` / `อีเมลลูกค้า`; the hint constant removed; ids and `terms.orderedByPhone/Email` unchanged.

- [ ] **Step 1: Revise the tests**: in `quotationOrderedByContact.test.jsx` rewrite the premise comment and the two `contactId: null` pins to "picks คุณสมชาย from the list → payload `contactId: <id>`, `orderedByName: null`"; add to the existing save test an assertion that the payload still carries typed `orderedByPhone/Email` under the new labels (`getByLabelText('โทรศัพท์ลูกค้า')`). In `QuotationEditorPage.test.jsx` revise `:798` the same way; `:773` (no `กรุณาระบุผู้สั่งซื้อ`) stays. Add ONE case: editor for a quotation whose ticket fetch failed → picker disabled, page renders (Review Focus 4). Revise the existing "omitContactHonorific is always false" pin (if present) to "follows the checkbox".
- [ ] **Step 2: Run RED**: `npx vitest run src/features/quotations/quotationOrderedByContact.test.jsx src/features/quotations/QuotationEditorPage.test.jsx -t "ผู้ติดต่อ|โทรศัพท์ลูกค้า|disabled"`.
- [ ] **Step 3: Implement** per the interface; delete `ORDERED_BY_FIELD_LABEL` and the two text inputs; mount `QuotationContactPicker` (ref kept for `flushPendingContactSave` in the download flush at `:2000`); checkbox under it wired to state.
- [ ] **Step 4: Run GREEN**: the two test files + `src/features/quotations/QuotationDealFields.test.jsx` if present; eslint on the two source files.
- [ ] **Step 5: Commit (on say-so)**: `feat(sales): quotation editor picks ผู้ติดต่อ from the customer's contacts; customer phone/email boxes relabelled`

### Task 5: Mock mirrors Part A

**Files:**
- Modify: `frontend/src/api/mockApi.js` (`resolveDealQuotationContact` `:6270-6286`; the document view model builder behind `QuotationDocumentView` "ติดต่อผู้สั่งซื้อ"/signature; any mock render of the signature name)
- Test: the existing `mockApi.dealQuotations*.test.js` case(s) that pin "falls back to the ticket contact" — revise to "no fallback"; one case for "signature name = contactName when picked, null on a DRAFT without one, typed name on a non-DRAFT".

- [ ] **Step 1: Revise/add tests** as above. **Step 2: RED. Step 3: Implement** (drop `?? ticket.contactId`; signature rule). **Step 4: GREEN** + `src/api/contract.test.js`. **Step 5: Commit (on say-so)**: `chore(mock): mirror per-quotation ผู้ติดต่อ — no deal fallback, signature-name rule`

### Task 6: Part A gate
- [ ] `npm run lint`; vitest on all touched test files + `QuotationDocumentView` tests; backend `DealQuotationIntegrationTest,DealQuotationRenderAdapterV3Test,DealQuotationOrderedByContactRenderTest,DealQuotationOrderedByContactIntegrationTest`.
- [ ] e2e-real `write-quotation.spec.js` (creates a contact via API and posts `contactId`; its "no ผู้สั่งซื้อ, blank snapshot" case must still pass) and `write-quote-stage-gates.spec.js` against a backend built from the branch.
- [ ] Opus whole-branch review; PR body with files / commands / results / "authz: no change" / risks (frozen-row rule relies on `docStatus`) / coverage list.

---

## Part B — `feat/deal-form-onepage`

### Task 7: IA pass (no code)
- [ ] Invoke the `information-architecture` skill on the deal creation form; write the outcome (section order, open/closed rules, what collapses to a one-line summary, mobile behaviour) to `.design/quotation-contact/IA-deal-form.md`, one page. Expected result: ลูกค้า → โครงการ → เสนอแก่ → รายการสินค้า → รายละเอียดดีล; first incomplete required section open by default; one open at a time; completed section header shows its value + ✓; required badge until done; "+ เพิ่ม…" first in every dropdown.

### Task 8: `SearchSelect` create-new row first; project list add-first

**Files:**
- Modify: `frontend/src/features/tickets/TicketCreateModal.jsx:268-296` (`SearchSelect` dropdown body), project view `:1300-1378` (add `+ เพิ่มโครงการใหม่` as the first row of the project list)
- Test: `frontend/src/features/tickets/TicketCreateModal.test.jsx`

- [ ] **Step 1: Revise/add tests**: find the existing customer-picker test that clicks `สร้างบริษัท / ลูกค้าใหม่` and extend it to assert that row is the FIRST child of the listbox (`compareDocumentPosition` against the first option); same for the project list with `เพิ่มโครงการใหม่`. Rename the create label to `เพิ่มลูกค้าใหม่` in the same test.
- [ ] **Step 2: RED. Step 3: Implement**: move the `onCreateNew` row above `options.map`, sticky (`sticky top-0`), label `เพิ่มลูกค้าใหม่`; project list gets the same first row opening the existing new-project form. **Step 4: GREEN.** **Step 5: Commit (on say-so)**: `fix(sales): deal form dropdowns put "+ เพิ่ม…" first`

### Task 9: One-page accordion; contact block removed

**Files:**
- Modify: `frontend/src/features/tickets/TicketCreateModal.jsx` (`view` state `:394`, `HubRow` `:319`, hub `:1155-1190`, views `:1233-1960`, `sectionDone` `:1105-1112`, contact block `:1434-1510`, `submit` `:1023-1102` (`contactId` removed), `jumpToField`)
- Test: `frontend/src/features/tickets/TicketCreateModal.test.jsx` (helpers `goToSection` `:132`, `chooseEntryChannel` `:135-139`, exact-payload test `:298`)

**Interfaces:**
- Produces: one `Accordion` of five `Section`s (`data-testid="deal-section-<key>"`, header `role="button"` with `aria-expanded`), keys `customer | project | channel | items | details`; `openSection` state (default: first incomplete required); header shows title, badge (`จำเป็น` / `ไม่บังคับ` / ✓) and the one-line summary when done; `jumpToField(key)` opens the owning section then focuses. Payload: no `contactId` key. The `เสนอแก่` section renders the same pill radiogroup as `QuotationRecipientField` (reuse its styling), options `ผู้ออกแบบนำ / เจ้าของตรง / ผู้ซื้อตรง`.

- [ ] **Step 1: Revise the tests**: rewrite `goToSection(title)` → `openSection(title)` (clicks the header with that name; `กลับ` calls become no-ops and are removed); `chooseEntryChannel` opens `เสนอแก่`; delete the handful of hub-specific assertions (hub row subtitles, `ผู้ติดต่อ & เสนอแก่` title); revise `:298` exact payload to omit `contactId`; add ONE case for Review Focus 5 (submit with no project → `deal-section-project` has `aria-expanded="true"` and the project field is focused) and ONE for "a completed section collapses to its value with ✓". No new test for contact removal — its absence is pinned by the payload test.
- [ ] **Step 2: Run RED** (`-t "aria-expanded|collapses"` first, then the file).
- [ ] **Step 3: Implement** per the interface; remove `HubRow`, `view`, `BackLink` usages, the contact block, `selectedContact`, `handleCreateContact` and the contact draft persistence; `canSubmit` no longer depends on `view`.
- [ ] **Step 4: Run GREEN**: whole `TicketCreateModal.test.jsx`; eslint.
- [ ] **Step 5: Commit (on say-so)**: `feat(sales): deal form is one page — accordion sections, no ผู้ติดต่อ`

### Task 10: Deal page drops the ผู้ติดต่อ row; mock mirror
- [ ] Revise the `TicketDetailPage.test.jsx` case that renders the ผู้เกี่ยวข้อง block (if it asserts ผู้ติดต่อ) → asserts the row is absent; RED; remove `TicketDetailPage.jsx:1739`; GREEN. Mock `tickets.create` (`mockApi.js:~8609`) ignores a missing `contactId` (already tolerant — assert via `contract.test.js` only). Commit (on say-so): `fix(sales): deal page no longer shows ผู้ติดต่อ`

### Task 11: Part B gate
- [ ] `npm run lint`; vitest on `TicketCreateModal.test.jsx`, `TicketDetailPage.test.jsx`, `contract.test.js`; e2e-real `write-quote-stage-gates.spec.js` test 8 (inline deal create from the quotation editor is a different component — confirm untouched) against a branch backend; manual phone-width check of the accordion on the QA stack.
- [ ] Opus whole-branch review; PR body as in Task 6, stating the `contactId` contract note.
