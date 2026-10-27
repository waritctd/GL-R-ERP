# ผู้ติดต่อ on the quotation — a picked contact for sales information; the deal form drops it and becomes one page

Owner rulings 2026-10-18 (Ploy), brainstormed in chat with four mockup rounds (contact picker v1–v4, deal form one-page v5 → B chosen) and one builder check. Branch `feat/quotation-contact-picker` off develop `e5603b2a`.

## Intent and the why

Reps keep track of *who they are dealing with* on each quotation. Today that person is a free-text box on the quotation editor (labelled ผู้ติดต่อ, stored as `ordered_by_name`, printed as the ผู้สั่งซื้อ signature name) plus two phone/email boxes. The name is retyped on every quotation and lives nowhere reusable. Separately the deal creation form asks for a ผู้ติดต่อหลัก that nothing downstream needs.

Ploy's words: *"ผู้ติดต่อ is just a record for sales information"*; *"it should be the phone and email of the customer, the old one is already correct"*; *"you don't have to take ผู้ติดต่อ as the recipient, sometimes it's not the same person"*; *"remove ผู้ติดต่อ completely from the deal, just have the recipient picker"*; *"it should stay changeable for every quotation under that deal"*; *"no need to keep the records of their position"*; *"email and phone should be optional"*; *"just store what the sales click"*; the signature name: *"it's the same person — rep prefers the field name ผู้ติดต่อ but printed as ผู้สั่งซื้อ"*.

## Goals

1. **Quotation editor: ผู้ติดต่อ becomes a picker** over the deal customer's contact records, replacing the free-text name box. Optional. "เพิ่มผู้ติดต่อใหม่" is pinned at the top of the dropdown; each row shows the contact's phone and email when known; the selected card shows them with inline แก้ไข/เพิ่ม (existing edit-in-place).
2. **Add-contact modal (mockup A):** title `เพิ่มผู้ติดต่อใหม่`, subtitle `ลูกค้า: <customer name>`; fields ชื่อ *, นามสกุล, เบอร์โทร, อีเมล (both optional); one checkbox `ใช้ชื่อเดียวกับลูกค้า` (copies the customer name into ชื่อ and locks it); buttons ยกเลิก / `เพิ่มและเลือกคนนี้`. **No position field.** Saves as a contact of the deal's customer and selects it.
3. **What prints does not change in source**: the quotation's Phone and Email boxes stay the **customer's** phone and email, typed as today and printed as today (owner ruling 2026-10-13 stands). The picked contact's **name** prints where a person's name already prints: the เรียน line (via the existing contact snapshot) and the ผู้สั่งซื้อ signature box (which today prints `ordered_by_name`).
4. **ผู้รับใบเสนอราคา (party) and ผู้ติดต่อ (person) stay independent.** The party radio is unchanged and keeps driving the deal stage.
5. **Deal creation form drops ผู้ติดต่อ entirely and becomes ONE page** (mockup B, accordion): sections ลูกค้า *, โครงการ *, เสนอแก่ *, รายการสินค้า, รายละเอียดดีล stacked as an accordion; one section open at a time; a finished section collapses to a single line showing its value with a tick; no hub rows that navigate to sub-views. ลูกค้า and โครงการ are searchable dropdowns; เสนอแก่ is the same pill radio as ผู้รับใบเสนอราคา (ผู้ออกแบบนำ / เจ้าของตรง / ผู้ซื้อตรง). **Every dropdown on the form puts its "+ เพิ่ม…" row FIRST** (เพิ่มลูกค้าใหม่, เพิ่มโครงการใหม่) so the rep never scrolls to the bottom to add. The deal page stops showing a ผู้ติดต่อ row.
6. **Per quotation, no pre-fill.** Every new quotation starts with ผู้ติดต่อ empty (owner ruling: "no need to pre-fill"); the rep picks, changes or clears it on each quotation. A revision/reorder still copies its parent's contact as today.
7. **Nothing becomes required**: no gate at save, ขออนุมัติ or issue. A quotation with no ผู้ติดต่อ prints the customer name alone on the เรียน line and **no name in the signature box** (just the dotted line) — owner ruling: "if a contact is not picked then the signature name should not be shown".

## Non-goals

- No migration, no change to issued quotations: the printed phone/email source is untouched, and old `ordered_by_name` values keep printing.
- No new table or party model; contacts stay `customers.contact` rows under one customer ("just store what the sales click"). The designer directory (หน่วยงาน code) is untouched.
- The three free-text party names on the deal (tracking panel) are untouched.
- The pricing-request form's ผู้สั่งซื้อ picker (optional `recipient_contact_id`) is untouched except for inheriting the shared picker's new look; it keeps seeding a PCR-origin quotation's contact.
- No change to contact authz (`DealEntryAccess`), customer master screens, or the `position` column (stays in the DB, no longer collected on the deal form, never collected by the new modal).

## Anti-goals

- Any new blocker on save or approval because of a contact.
- Changing which phone/email print, or re-rendering old documents differently.
- Tying ผู้ติดต่อ to the recipient party, or putting a required contact back on the deal form.
- Any change to the printed PDF beyond the signature-name rule: relabelling the editor's phone/email boxes must leave the PDF's own labels and values byte-identical.

## Constraints

- Sales-flow redesign is authorised to change workflow and sales API contracts; each change is stated in the PR body. No schema change expected; if one is needed it is a forward-only `Vnnn`.
- Test-first; revise existing tests, delete stale ones, add new only for distinct behaviour. No commit/push without Ploy's say-so.
- Tailwind tokens from `frontend/src/index.css`; no page-specific CSS. Thai copy as given.
- Public repo: no real customer data in fixtures.

## The decided how

### Backend
- `DealQuotationService.resolveContact` precedence becomes: request `contactId` → the row's existing `contact_id` → none. It no longer reads `ticket.contact_id` (the deal form stops setting it; the column and `changeCustomer`'s clearing stay). No pre-fill from other quotations.
- **Signature name** (mechanism corrected during execution, 2026-10-18 — rows issued before this change already carry an inherited contact snapshot, so a renderer rule of "contact name first" would have changed issued PDFs): **the renderer is untouched**; `ordered_by_name` becomes a server-derived mirror of the resolved contact's name on every DRAFT write (`create`, `update`, `createFromPricingRequest`, and `refreshDraftContactSnapshot` when a contact's name is refreshed), null when no contact. `UpsertDealQuotationRequest.orderedByName` is ignored. Issued/approved rows are never re-saved, so they print exactly what they printed; a draft without a contact prints the dotted line after its next save; a draft with a contact prints the contact's name. `omit_contact_honorific` keeps governing "คุณ" on the เรียน line; the checkbox comes back under the editor's picker.
- `ordered_by_phone` / `ordered_by_email` (the customer's phone/email boxes) and `printedPhone/printedEmail`: **unchanged**.
- No new validation anywhere; `create`/`update`/`submit` stay lenient. `refreshDraftContactSnapshot` keeps refreshing DRAFT rows when a contact is edited; non-DRAFT rows stay frozen.
- Contact API unchanged. Mock mirrors the precedence change and the signature-name rule.

### Frontend
- **Shared picker** (evolve `QuotationContactPicker`, used by the quotation editor and the PCR form): label `ผู้ติดต่อ`, hint `ชื่อจะพิมพ์เป็นผู้สั่งซื้อบนใบเสนอราคา`; dropdown: "เพิ่มผู้ติดต่อใหม่" first, then a client-side search box (name / phone / email), then contacts with phone · email under the name; selected card with inline แก้ไข/เพิ่ม for เบอร์ and อีเมล; the "ไม่เติม "คุณ"" checkbox under it on the quotation editor.
- **Modal A** as in Goal 2, shared by both consumers; replaces the picker's current inline new-contact form.
- **Quotation editor**: picker replaces the `orderedByName` text box in both places it appears (inline and card); the Phone/Email boxes stay and are relabelled to say whose they are: `โทรศัพท์ลูกค้า` / `อีเมลลูกค้า` with the existing hint; payload sends `contactId` (picked or null) and no longer sends a typed `orderedByName` for new input.
- **Deal creation form** (`TicketCreateModal`): replace the hub-and-sub-view structure with the accordion above (one component, five sections, `open` state = first incomplete required section by default; clicking a collapsed header opens it and collapses the others; the required-badge / missing-field jump logic is kept but points at sections, not views). Remove the ผู้ติดต่อหลัก block and its inline form; payload omits `contactId`. The customer and project pickers become searchable dropdowns with "+ เพิ่ม…" pinned first (reuse the existing create-customer / create-project flows behind that row). Use the `information-architecture` skill before restructuring, per CLAUDE.md. Deal page: remove the read-only ผู้ติดต่อ row.
- **Picker dropdowns everywhere** (customer, project, contact): the "+ เพิ่ม…" row is the first row, above search results — same rule as the contact picker.

### Copy
`ผู้ติดต่อ` with the ONLY hint on the form: `ชื่อจะพิมพ์เป็นผู้สั่งซื้อบนใบเสนอราคา` (no other explanatory text on these screens — owner ask); `เพิ่มผู้ติดต่อใหม่`, `เพิ่มลูกค้าใหม่`, `เพิ่มโครงการใหม่`, `ใช้ชื่อเดียวกับลูกค้า`, `เพิ่มและเลือกคนนี้`, `ยกเลิก`, `โทรศัพท์ลูกค้า`, `อีเมลลูกค้า`, `เสนอแก่`, `ดีลนี้เสนอแก่ใคร`, `สร้างดีล`.

## Left to the builder
- `TicketCreateModal.test.jsx` has 73 cases that navigate by hub-row title (`goToSection('ผู้ติดต่อ & เสนอแก่')` etc.); they must be revised to the accordion (open a section by its header), not duplicated. Expect the bulk of the frontend test work here.
- Mobile layout of the accordion (the form is used on phones): headers stay 44px tall; an open section scrolls into view.
- Adding `customerId` to `DealQuotationDto` if the editor cannot otherwise load the customer's contacts for a user without deal read rights.
- Test placement following the existing classes (`DealQuotationIntegrationTest` contact cases, `DealQuotationOrderedByContactRenderTest`, `QuotationEditorPage.test.jsx`, `quotationOrderedByContact.test.jsx`, `QuotationContactPicker.test.jsx`, `TicketCreateModal.test.jsx`), revising rather than duplicating.

## Calls I made (Ploy to confirm or strike)
1. ~~Pre-fill from the latest quotation~~ — struck by Ploy: no pre-fill.
2. The Phone/Email boxes are relabelled โทรศัพท์ลูกค้า / อีเมลลูกค้า in the editor only; the PDF renders identically (confirmed by Ploy).
3. The "ไม่เติม "คุณ"" checkbox returns under the quotation editor's picker (today only the PCR form has it, and the editor silently overwrites it with false).
4. The deal page stops showing ผู้ติดต่อ at all.
5. Signature name: confirmed by Ploy — no contact picked, no name shown (DRAFTs); issued rows stay frozen.
