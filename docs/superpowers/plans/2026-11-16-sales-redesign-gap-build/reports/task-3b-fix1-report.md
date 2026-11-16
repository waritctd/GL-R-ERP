# Task 3b — fix round 1 report

**Status: DONE.** All six rulings are fixed and nothing is committed. The worktree is `deal-doc-dialogs`, on top of 2ab27ffd. No request, calculation, opener or permission was changed.

## Fixes
| Ruling | Change |
|---|---|
| F1 | IR warning is now exactly "ออกเลข IR แล้วลบไม่ได้ · แก้ได้ด้วยการออกฉบับแก้ไขเท่านั้น". |
| F2 | On the notice page's confirm: the subtotal row and the bold "…รวม VAT" line are gone. It shows the old figures (deposit, VAT, total) in the drawn layout, under "ยอดโดยประมาณ · ยอดจริงตามเอกสารที่ระบบออก". The deal page's confirm keeps the full summary. |
| F4 | The warning is now the first thing in the scrolling body of the deposit (both openers) and IR confirms. **The remaining-invoice and delivery dialogs have no "cannot be undone" warning, before or after, so neither got one.** |
| F5 | At ≤520px the delivery table folds สั่ง/ส่งแล้ว/เหลือส่ง into a line under the name, leaving two columns: the name and the ส่งครั้งนี้ input. |
| F6 | The remaining-invoice footer's source sentence is dropped: nothing in the dialog says whether a deal's lines came from the notice, the quotation, or legacy prices. The delivery note is now "ถ้าบันทึกครั้งนี้ทำให้ส่งครบทุกรายการ ระบบจะบันทึกว่าส่งครบ เลื่อนงานไป S9 (ถ้ายังไม่ถึง) และแจ้งฝ่ายบัญชี", naming S9 once. |

## Tests and results
- **Red run against 2ab27ffd:** 14 failed, each for the right reason (missing element or old text), 267 passed.
- **Adapted tests:**
  - Three IR assertions on "ย้อนกลับไม่ได้" now check the F1 text.
  - My own page summary test and two footer tests changed to match the new behaviour.
  - Delivery figure cells are now read from columns 1–3.
- **Commands:**
  - Targeted run: 573 passed.
  - `npm run lint`: exit 0.
  - `npm test`: 305 files, 6719 tests passed.
  - `npm run build`: exit 0.

## Mutations
All 11 killed:
- The four earlier survivors: V8, V11, W3, V13.
- One per ruling: F1, F2, F4 for deposit, F4 for IR, F5, and F6 for both the footer and the delivery note.

The sources were restored and checked. Runner: `scratchpad/mut3b-fix1.mjs`.

## On-screen check
Run with `VITE_USE_MOCKS=true` on port 5261, now stopped.
- **375×560:** the deposit and IR warnings show inside the visible body, with the confirm button on screen.
- **320px delivery:** the table does not scroll sideways (248 = 248) and the input sits inside the body.

This check is not permission evidence.
