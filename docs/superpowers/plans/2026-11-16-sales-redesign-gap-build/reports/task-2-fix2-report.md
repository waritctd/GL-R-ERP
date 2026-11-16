# Task 2 — fix round 2 report (on top of ad04ec86)

Status **DONE**. Uncommitted, nothing staged. Frontend only, no Maven. Lint exit 0; `npm test` 303 files / 6589 passed;
build exit 0. Red logs: `scratchpad/red/fix2-red-*.txt`.

1. **Deal-to-deal leak.** `TicketDetailRoute` now renders the page with `key={id}`, so changing deals mounts a fresh
   page and its whole subtree.
   - Red first: an item edited on 701, then a switch to ภาพรวม, then navigating to 702 showed 701's row and
     "บันทึกการแก้ไข" on 702.
   - A → B → A: the edit on A is **gone**, as decided.
   - Within one deal the edit still survives a tab switch (tested through the real route).
   - Mutation: removing the key turns exactly those two leak tests red; the file was restored byte-identical.
   - Audit:
     - All page state resets with the key: item edit, dialogs, comment and revise drafts, condensed header, bid modal.
     - Child state resets too: the timeline filter and "+ บันทึก" draft, the call/visit dialog drafts, the stage-panel
       modals, and the ข้อมูลงาน edit draft.
     - The active tab lives in the URL; a new deal's link has its own `?tab=` (or none).
     - The query cache is keyed per deal id.
     - No localStorage is used.
     - Nothing else carries over.
2. **Four server-gated buttons.** The card title is now the button's own label (ลูกค้ายืนยัน, ยืนยันชำระครบ (Final
   Payment), ยืนยันพร้อมปิดงาน, ตรวจสอบและปิดงาน). The explanatory sentence appears exactly once, beneath it. One test per
   button, each red first.
3. **Lost deal with nothing recorded.** It always shows "เหตุผล: -" and "ปิดเมื่อ -", as the old banner did (tested).
4. **Single-party quotation trigger.** Test added: it is the same plain button as the primary and opens the same
   form. It passed on first run because it pins behaviour that already worked; the multi-party test already covered
   the shared builder.

Left untouched, per your rulings: the DealPartiesPanel per-party link, CallModal's "ดีลนี้", and `OWNER_ONLY_ACTIONS`.
