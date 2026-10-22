# CR-1 (GLA-167) frontend — test coverage list (written BEFORE implementation)

All tests below were written first and run RED before any production code changed. Red = the
assertion/lookup that fails is the missing behaviour (missing API method, missing element/label,
wrong label), not an import or syntax error. Everything is UI/mock level: **role assertions are
UI-level only (mockApi authz is not authoritative)** — the real enforcement is the Java integration
tests for `FactoryQuoteService` / `LeadTimeChangeService`.

## Red-run evidence (before implementation)

| File | Red |
|---|---|
| `src/api/mockApi.factoryContact.test.js` (new, 24) | 24 failed — `api.pricingRequests.markFactoryQuoteContacted is not a function`, `api.leadTimeChanges` undefined, EUR/PER_SQM not echoed |
| `src/features/pricingRequests/PricingRequestDetailPage.test.jsx` | 38 failed (5 rewritten old-flow tests + 33 new) — e.g. `Unable to find role=button name=สร้างเมล`, `Unable to find testid pcr-factory-terms-91`, 3s `findByTestId` timeouts for lead-time UI |
| `src/features/pricingRequests/PricingRequestCreateModal.test.jsx` | 19 failed — 7 new + 12 existing happy-path tests that now need `สกุลเงิน (ราคาโรงงาน)` / `หน่วยราคา` (`fillRequiredFields` updated) |
| `src/features/tickets/DealFulfilmentPanel.test.jsx` | 5 failed — label `สร้างใบ IR`, 4 auto-download assertions (`download` never called) |
| `src/features/tickets/DealDocumentRegister.test.jsx` | 5 failed — `register-import-requests` section absent |
| `src/api/serverContract.test.js` | 3 failed — hrApi still calls `POST /api/factory-quotes/{}/send` (removed server side); 7 new server endpoints not called; `ui-reachable.json` stale |
| `src/features/fulfilment/ImportFulfilmentPage.test.jsx` | 1 new test **passes** before implementation (characterization of existing per-factory PDF buttons, pinned deliberately) |
| 8 existing `mockApi.*.test.js` files | now call `markFactoryQuoteContacted` after `generateFactoryEmailDrafts` (B-R2); red with `is not a function` until the mock exists |

## Test cases and what they pin

### `mockApi.factoryContact.test.js` (mirrors `FactoryQuoteService#markContacted/#receive`, `LeadTimeChangeService`)
- items echo `requestedCurrency`/`requestedPriceUnitBasis`; draft seeded from them (EUR / PER_SQM).
- `sendFactoryQuote` is gone; `markFactoryQuoteContacted`: DRAFT→REQUESTED + date/note/by/at + request → AWAITING_FACTORY_RESPONSE; 409 on second call and recorded note unchanged (R7); 400 future / missing date; sales 403, CEO allowed (also edit draft + generate drafts, B-R1).
- `receive`: 409 on DRAFT (B-R2); 409 on currency mismatch; 409 on unit mismatch; OK when matching; legacy line (no terms) accepts anything.
- lead-time: create snapshots old range / PENDING / version 1; line untouched until approved; one PENDING per quote (409); validation 400 (reason, empty lines, min>max); update bumps version; withdraw frees the factory; approve (owning rep) writes new range; stale `expectedVersion` 409 changes nothing; reject needs reason; **CEO and import cannot approve/reject (B-R3)**; only import creates/updates/withdraws; list readable by import/ceo/sales/sales_manager, not account.

### `PricingRequestDetailPage.test.jsx` (factory section, UI)
- Card: chip `ยังไม่ติดต่อ`; `สร้างเมล` + `ติดต่อโรงงานแล้ว`; retired labels (`ร่างอีเมล`, `ดูอีเมล`, `ส่งแล้ว`) absent.
- Locked terms strip `EUR · ต่อ ตร.ม. · ตามคำขอของฝ่ายขาย`; currency/unit comboboxes removed for lines with terms; legacy line keeps both selects (characterization, passes pre-impl); inputs disabled + hint `กด ติดต่อโรงงานแล้ว ก่อนกรอกราคา`; contacted chip `ติดต่อแล้ว 1 ต.ค. · <name>`, note shown, inputs unlocked, **no ติดต่อโรงงานแล้ว button and no undo (R7)**; `ยืนยันราคาแล้ว`; receive payload carries EUR/PER_SQM; CEO has no inputs/confirm/lead-time button.
- Rewritten old tests: draft edit via `สร้างเมล`/`บันทึกร่าง`; lock hint testid `pcr-await-contact-*`; DRAFT stays locked **past the window** (B-R2 reverses #1062) with no contact button; mail modal read-only when contacted / outside window.
- Contact dialog: date defaults to today (Bangkok) with `max`=today; confirm → `markFactoryQuoteContacted(id,{contactedOn,note})` + refetch of quotes and request (F); blank note omitted; future date → confirm disabled, nothing sent; cancel; 409 → error toast; CEO can use both buttons (R5).
- `สร้างเมล` modal: ถึง prefilled from factory email (R8), หัวข้อ, เนื้อหา; only `บันทึกร่าง`/`คัดลอกเมล`(+ปิด); copy changes no status; close changes no status; ticked request attachments listed.
- Lead-time (import): per-line `ระยะเวลานำเข้า`; panel with new min/max, required reason, non-stock lines pre-ticked (stock line never offered); submit sends ticked lines; unticked excluded; per-line override; min>max / empty reason blocks; pending shows `75–90 → 120–150 วัน · รอฝ่ายขายอนุมัติ`, `แก้ไข`/`ถอนคำขอ`, no second request; withdraw; edit → `update`; compact approved/rejected history with reason; **CEO read-only badge**.
- Lead-time (deciders): owning rep / sales_manager see one banner (factory, reason, old→new) with `อนุมัติ`/`ไม่อนุมัติ`; approve sends `expectedVersion` and refreshes the request; reject needs reason then sends reason+version; **409 → `คำขอถูกแก้ไขแล้ว กรุณาตรวจสอบอีกครั้ง` + refetch**; non-owning sales / CEO / import see no decide controls; history-only → no banner.
- IR row on the factory card (R9): `IR26001 · … · PDF` downloads, `ฉบับร่าง`, no create/issue/revise control for import, matches factory by id else name, ignores SUPERSEDED/other factories, absent when none.

### `PricingRequestCreateModal.test.jsx` (F4)
selects blank for hand-typed line; blocked with per-row errors; catalogue pick auto-fills (EUR / `per_sqm`→PER_SQM) and sales may change before submit; unknown catalogue unit leaves unit blank; hand-typed line payload; edit seeds + resends; legacy persisted line must be completed before save.

### `DealFulfilmentPanel.test.jsx` / `DealDocumentRegister.test.jsx` / `ImportFulfilmentPage.test.jsx`
`สร้างใบ IR` (+ `สร้างใบ IR โรงงานที่ยังไม่มี`) relabel; after create, PDF of **each newly created** IR downloads (existing ones skipped); failed PDF keeps the create + error toast; invalidation of stored IRs and `['tickets','list']` (characterization). Register: 5th section `ใบขอซื้อ (รายโรงงาน)`, one row per IR, `ฉบับร่าง`, SUPERSEDED hidden, PDF download, empty line, read gate (import/ceo/sales_manager/owner yes; other sales/account no and no fetch). `/fulfilment`: per-factory internal + factory PDF download pinned.

### API parity
`contract.test.js` (hrApi↔mockApi, same arity) and `serverContract.test.js` (hrApi↔springdoc, ui-reachable.json) — red now, green after the API layer change.

## Deliberately NOT covered
- **Authz correctness** (who may mark contacted / decide a lead-time change): UI conditionals only; real enforcement = Java integration tests. Mock authz is approximate and not evidence.
- Visual layout / 375px: not unit-testable here; listed for manual browser verification in the handoff. The mock-frontend e2e suite no longer exists.
- Real PDF bytes / clipboard in a real browser; Bangkok-vs-browser-timezone edge at 00:00–07:00 is covered only by computing "today" with `Intl` in Asia/Bangkok.
- `/fulfilment` still lists only deals with ISSUED rows: a deal whose IRs are all DRAFT is not shown there (unchanged; flagged in the handoff, not changed).
- Factory-master default currency/unit autofill for hand-typed lines: that endpoint is import/CEO-only, so sales has no source — the field stays blank + required.
- Quotation re-issue after acceptance (B-R5): out of CR-1 scope.

## Green run (after implementation)
`cd frontend && npm run lint` 0 errors (1 pre-existing warning in `QuotationContactPicker.test.jsx`, untouched) · `npm test` 220 files / 3995 tests passed · `npm run build` ok.
Two existing tests (`PricingRequestPanel.test.jsx` edit-draft, `PricingRequestDetailPage.test.jsx` revision modal) needed their fixture line to carry `requestedCurrency`/`requestedPriceUnitBasis`: a legacy line must now be completed before it can be saved (intended, pinned in `PricingRequestCreateModal.test.jsx`).
