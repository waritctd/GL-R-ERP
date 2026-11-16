# Task 2 — fix round 1 report (deal page, `feat/deal-page-redesign` on top of f29cd4cd)

Status: **DONE**. Final: lint exit 0; `npm test` 303 files / 6580 passed; build exit 0. Uncommitted, nothing staged. Frontend only. Maven not run. Permission claims are checked against
mocks only (not against the Java service).

**Red first.** I recorded 38 failing tests before implementing: 36 in the redesign, ImportRequestFactoryCard,
nextStep and dealSteps tests, and 2 in TicketDetailPage.test (logs are in `scratchpad/red/fix1-red-*.txt`). After the
fixes, everything passed: lint exit 0, `npm test` exit 0, build exit 0 (log: `scratchpad/fix1-final.txt`).

1. **Quotation entry.** The 5560009a guard is restored with its assertions unchanged; it now runs on ใบเสนอราคา and
   ไฟล์. There is no `/quotations/new` link on the page. The ใบเสนอราคา card gets a second trigger only while the
   primary button is the route choice (เสนอราคา<party>). Both are built from the same `items` and `openFor` handler
   (`RouteChoiceTrigger`).
   - Tests cover the trigger's absence while the primary is create_pcr, with a live direct quotation, after the order
     is confirmed, with a live later-party request, on a paused deal, and for another rep or account. One test checks it
     is present and opens the same choice.
   - To check: if the mocked request list includes another rep's requests (the server would never return them), the
     **primary** itself already offers เสนอราคา to a non-owner. That predates this round (`OWNER_ONLY_ACTIONS`).
2. **⋯ menu** now uses `mobilePlacement="down"`. Tested by behaviour: the menu's `top` is set and `bottom` is not.
3. **Padding** is `mobile:pb-36` exactly when the card has a button (resume, reopen and ไปที่… included).
4. **Lost and paused facts** are back in the card: the reason, the lost date, and "ขั้นเดิม N. <label>". Paused deals
   also show the stage label.
5. **Item edits survive a tab switch.** `useDealItemsEdit` keeps the edit state in the page (edit → switch → back is
   tested).
6. **Stepper labels** come from `DealStage.java`. The rep moves S1, S2, S5, S8 and S9 (เซลส์ย้ายเอง); documents move S3,
   S6 and S7 (ย้ายตามเอกสาร). Lines this is based on:
   - "Document-driven — … advanceStageForCustomerQuotationIssue → QUOTE_DESIGN_SIDE / QUOTE_OWNER / QUOTE_BUYER" (156–157)
   - "confirmCustomer → ORDER_RECEIVED, reconcilePaymentStatus → DEPOSIT_RECEIVED, issueImportRequest → PROCUREMENT" (151–153)
   - "DELIVERY_SCHEDULING and DELIVERED are deliberately absent … the rep also reaches both by hand … presenting them as 'the system does this for you' would be wrong" (166–169)
   - `AUTO_ADVANCED = Set.of(ORDER_RECEIVED, DEPOSIT_RECEIVED, PROCUREMENT, CLOSED_PAID, QUOTE_DESIGN_SIDE, QUOTE_OWNER, QUOTE_BUYER)` (171–179). AWAITING_BUYER is not in it.

   The next-step hints were corrected to match.
7. **Wording.** Changed to งาน: the cancel toast, the stepper strings, EntryChannelFix (through a `noun` prop, so
   UpdateStageModal keeps ดีล), and the weight note. On ImportDealPage nothing changed: ImportRequestFactoryCard takes
   `dealNoun` (default ดีล) and an opt-in chip. The entry-channel enum label still reads ดีล.
8. **ที่มา** shows ยังไม่กำหนด while `procurementSource.pending`.
9. **Low items:**
   - The card title is the resolved action's label.
   - ORDER_RECEIVED has its own hint when the deposit is bypassed.
   - There is one ถัดไป line, inside the card (`nextStageLine`/`NextStageLine` extracted from DealStagePanel).
   - The h1 falls back to the deal code.
   - The ดูอย่างเดียว chip appears only on a card that offers no edit.
   - The register's empty branch keeps its header action.
10. **Mutation tests.** Tests that check behaviour: M0, M1, M3, M10, M12, M42 and item 8. Tests that check class
    presence only: M32–M35 (reduced motion, 44px targets, the stepper's scroll wrapper).

**Mutation re-run (16):** 13 killed; M04, M09 and M11 are equivalent, as the reviewer judged.
- M04: owner, sales_manager and CEO already imply deal tracking.
- M09: no primary button is served on a non-ACTIVE deal.
- M11: closed and none steps have a null tab, which the visibility check blocks.
- M01/M02 targeted code that was removed; their successor mutation is killed.

**Adapted tests:**
- dealSteps/nextStep expectations, from the backend facts.
- Card-title tests.
- The guard test, restored.
- Stepper/EntryChannelFix strings, plus button queries widened to /แก้ช่องทาง/.
- The ship-tab chip test is now viewer-specific.
