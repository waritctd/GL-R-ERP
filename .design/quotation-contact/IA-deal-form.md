# IA — ฟอร์มสร้างดีล as one page (Part B, Task 7)

Information-architecture pass for `TicketCreateModal` (`frontend/src/features/tickets/TicketCreateModal.jsx`), per DESIGN.md Goal 5 and mockup B (accordion). Structural decisions only; visual tokens follow `frontend/src/index.css`. Ploy's rulings 2026-10-18 already answer the skill's interview questions (one page, accordion B, "+ เพิ่ม…" first, no ผู้ติดต่อ), so none are re-asked here.

## What exists today (and stays)
Modal `สร้างดีลใหม่` (size lg) → optional **draft picker** (GLA-19, shown first when saved drafts exist; unchanged) → a **hub** of five rows (`HubRow`) that each navigate to a **sub-view** with a `กลับ` link; footer `บันทึกร่าง` / `สร้างดีล` only on the hub; `SafeForm` restricts implicit (Enter) submission to the hub. Validation (`validateTicketForm`) + the post-submit `ยังกรอกไม่ครบ N รายการ` block with `ไปที่ขั้นตอน` jumps (`jumpToField`) + debounced autosave stay as they are.

## New structure — one page, five sections, one open at a time
```
Modal สร้างดีลใหม่
├─ (draft picker, when drafts exist — unchanged)
├─ [autosave notice line — unchanged]
├─ [ยังกรอกไม่ครบ block — only after a failed submit — unchanged, jumps open the owning section]
├─ Section customer  ลูกค้า          required
├─ Section project   โครงการ         required   (needs ลูกค้า first)
├─ Section entryChannel เสนอแก่      required
├─ Section items     รายการสินค้า     optional
├─ Section details   รายละเอียดดีล    optional
├─ [info line ดีลที่สร้างยังไม่มีราคา… — unchanged]
└─ Footer: บันทึกร่าง · สร้างดีล (always present; item-editor footer ลบ / บันทึกรายการ while an item is being edited)
```
Order is fixed: ลูกค้า → โครงการ → เสนอแก่ → รายการสินค้า → รายละเอียดดีล (the order `validateTicketForm` reports in). No hub, no sub-views, no `กลับ` between sections. One `sections` table in the component holds each section's id, title, required flag, `done(state)`, `summary(state)`, body and optional `below` slot (the duplicate-deal warning sits in โครงการ's, under its card). Each section id equals the `fieldErrors` key it owns (`items` owns every `items.<i>.<field>`). Only the open section's body renders.

### Section header (the collapsed line)
APG accordion structure, as `CollapsibleSection` documents it: `<h3><button id="deal-section-<id>-header" aria-expanded aria-controls>` inside `data-testid="deal-section-<id>"`, the open body a `role="region"` labelled by that button; min-height 44 px (phones), showing left→right: **title** · **one-line summary when done** (muted) · **badge** · chevron. Badge rule: required & not done → `จำเป็น`; optional & not done → `ไม่บังคับ`; done → ✓ (success). Summaries: customer = name (+ เลขภาษี muted); project = name; entryChannel = option label; items = `N รายการ`; details = ชื่อดีล, else ความสำคัญ label when ≠ ปกติ, else `มีหมายเหตุ`. A summary shows only once the section is done.

### Open / close rules
- **Default open** = first incomplete required section (customer → project → entryChannel); when all three are done nothing is forced open.
- **One open path**: a header click, an auto-advance, a `jumpToField` jump, restoring a draft and starting a new one all change the open section through `goToSection()`. Leaving รายการสินค้า closes the item editor through the same check as its other exits, so a จากสต็อก row with no price keeps the rep in the editor. The check reads the editor row and items as last committed (refs), not the caller's closure, so it also holds for an auto-advance that lands after an await (a late customer/project create). That keeps "editor open ⇒ รายการสินค้า open" true on every path, which is why the footer's editor buttons need no section check.
- **Picking the value of a required section auto-advances**: that section collapses to its summary + ✓ and the next incomplete required section opens (mockup B: ลูกค้า ✓, โครงการ open), judged on the latest committed values, so a customer/project created after an await does not reopen a section finished meanwhile. Optional sections open only by click.
- Clicking a collapsed header opens it and collapses the one that was open (one open at a time). Clicking the already-open header is a no-op (builder ruling: with one-open-at-a-time a toggle buys nothing and would break programmatic opens).
- `jumpToField(key)` opens the section owning `key` (its part before the first dot; `items.<i>.*` also opens row `i`'s editor) then focuses the field, reusing `pendingFocusKey`. The `ไปที่ขั้นตอน` link text is unchanged (tests pin it).
- โครงการ before ลูกค้า: the section body shows the existing `ต้องเลือกลูกค้าก่อน — โครงการต้องผูกกับลูกค้าเสมอ` + `ไปที่ ลูกค้า` (which now opens the ลูกค้า section). Clearing the customer still clears the project.
- Restoring a draft (`เปิดต่อ`) lands on the accordion with sections ticked from the draft; open = first incomplete required. A draft's old `contact` field is ignored on read, no longer written, and does not count as draft data.
- On phones, opening a section scrolls its header into view (`scrollIntoView({ block: 'nearest' })`, in `Section`'s own effect, which React runs before the form's pending-focus effect — so a jumped-to field's smooth scroll lands last); the modal's `min-h-[26rem]` body rule is kept (the accordion is taller anyway, and the customer dropdown still needs the room).

### Section bodies
- **ลูกค้า**: `SearchSelect` as today, label `ลูกค้า *`, create row **first and sticky**, label `เพิ่มลูกค้าใหม่` → opens the existing inline new-customer form beneath.
- **โครงการ**: first row/pill **`+ เพิ่มโครงการใหม่`** → opens the existing inline new-project form; then the project pills; the selected project chip as today.
- **เสนอแก่**: body label `ดีลนี้เสนอแก่ใคร *`; the existing 3-column radiogroup `aria-label="เสนอแก่"` with `ผู้ออกแบบนำ / เจ้าของตรง / ผู้ซื้อตรง` (`ENTRY_CHANNEL_OPTIONS`) is kept as-is (builder ruling: already tested, dashed 'undecided' idiom; restyling to `QuotationRecipientField` pills is cosmetic and deferred). The old sub-hint `ดีลนี้เข้ามาทางไหน — …` is dropped (the label carries the question); the ผู้รับใบเสนอราคา disambiguation box under it stays (not contact copy). **No ผู้ติดต่อหลัก block, no inline add-contact form.**
- **รายการสินค้า**: the existing list / empty state / item editor, rendered inside the section body (the editor replaces the list while editing; its footer buttons stay). The editor keeps its `กลับไปรายการสินค้า` link back to the list (builder ruling: removing it would drop an editor exit); it is now an inline button, not the `BackLink` component.
- **รายละเอียดดีล**: ชื่อดีล, ความสำคัญ, หมายเหตุ, nextFollowUp as today.

### Submission
- Payload drops `contactId` (server keeps it nullable — stated in the PR body). Everything else identical.
- Only the `สร้างดีล` button creates a deal: Enter inside any `<input>` on the page is swallowed by the form's `onKeyDown` (textareas keep Enter); `SafeForm canSubmit={!showPicker}`.
- The `ยังกรอกไม่ครบ` block and per-field `aria-invalid`/`role="alert"` wiring are unchanged.

## Deal page and the other readers of the deal contact (Ploy 2026-10-19: "you can drop them here")
`TicketDetailPage` ผู้เกี่ยวข้อง block: the `ผู้ติดต่อ` `FieldRow` is removed. `FinanceDealPage` header no longer prints the deal's contact name. `PricingRequestCreateModal` no longer seeds its ผู้ติดต่อ picker from the deal's contact on create (an existing request's own `recipientContactId` still seeds on edit/revision).

## Naming (UI labels, verbatim)
| Concept | Label |
|---|---|
| customer section / field | ลูกค้า · create row `เพิ่มลูกค้าใหม่` |
| project section / field | โครงการ · create row `เพิ่มโครงการใหม่` |
| entryChannel section | `เสนอแก่` · body question `ดีลนี้เสนอแก่ใคร` |
| items / details sections | รายการสินค้า · รายละเอียดดีล |
| badges | `จำเป็น` · `ไม่บังคับ` · ✓ |
| primary action | `สร้างดีล` |

## Removed
`HubRow`, the `BackLink` component (inlined into its one remaining use, see รายการสินค้า), the `view` state and `viewForFieldKey`'s view routing, `renderHub/…View` split, the ผู้ติดต่อหลัก block (`selectedContact`, `handleCreateContact`, `creatingContact`, contact draft field, `api.customers.contacts` fetch on this form), the เสนอแก่ sub-hint. The quotation editor's inline deal-create component is a different component and is untouched.
