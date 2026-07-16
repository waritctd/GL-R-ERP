# Agent Handoff

## Task
User report: "I can download it but the format is wrong for the pdf part." Investigation
(4 PDF paths traced end-to-end) found the quotation PDF was a bare left-aligned text dump —
no table, no template structure — while the xlsx path fills the official company template.
On the deployed demo (Vercel rewrites `/api/*` → Render) the PDF button returns a real PDF,
so "wrong format" = wrong document layout. Fix the renderer.

## Branch
`fix/quotation-pdf-layout` (off main `0dc144b`)

## What changed

### 1. `PdfDocumentWriter` (shared: quotation, deposit notice, payslip)
- **Control-char sanitization everywhere** — PDFBox `showText()` throws on U+000A
  (`PDType0Font`), so a multiline customer address (a `<textarea>` in DepositNoticePage)
  turned downloads into 500s. Values are now split on newlines and residual control chars
  replaced.
- **Width-aware wrapping in `text()`** — lines wider than the printable area previously
  rendered clipped off the right page edge. Includes hard-breaking for long Thai runs
  (no spaces to split on).
- New layout primitives for table rendering: `textAt` (positioned), `textRight`
  (right-aligned), `newLine`, `width`, `wrap`, `rule`, `ensureRoom`, `left`/`right`.
  Existing API (`text`, `gap`, `loadFont`, `toBytes`) unchanged, so
  DepositNoticeRenderer/PayslipRenderer gain the safety fixes without layout changes.

### 2. `QuotationRenderer.toPdf` — real document layout
Company header → centered "ใบเสนอราคา / QUOTATION" title → right-aligned number/date meta →
customer block (name, wrapped address, tax id, phone, project) → items table (ลำดับ | รายการ
(wrapped) | จำนวน | หน่วย | ราคา/หน่วย | จำนวนเงิน) with header rules, per-item page-break
handling and header re-draw on continuation pages → right-aligned totals (รวมเป็นเงิน /
VAT 7% / รวมทั้งสิ้น, mirroring xlsx I38–I40) → terms line → signature blocks.
The PDF renders **all** items (paginates) — unlike the xlsx template's 15-row cap.

### 3. Latent NPE fixed in `buildDesc`
`List.of(brand, model, color, texture, size)` throws NPE on null elements — color/texture/
size are nullable columns, so any manually-entered item (no catalog pick) 500'd BOTH the
xlsx and PDF downloads. Now `Stream.of` (null-tolerant filter). Found by the new tests.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/common/PdfDocumentWriter.java` — rewrite (API superset)
- `backend/src/main/java/th/co/glr/hr/ticket/QuotationRenderer.java` — `toPdf` layout rebuild
  + `buildDesc` NPE fix (xlsx path untouched except via buildDesc fix)
- `backend/src/test/java/th/co/glr/hr/ticket/QuotationRendererTest.java` — NEW (there were
  zero QuotationRenderer tests): formatted-layout content assertions, multiline-address
  survival, long-description wrapping, >15-items pagination

## Commands Run
```bash
cd backend && ./mvnw -B clean verify
# visual check: rendered a realistic 3-item sample (Thai names, multiline address,
# extra-long description) via a throwaway test, converted with sips, inspected the PNG —
# layout verified: aligned table columns, wrapped desc, correct totals 424,100/29,687/453,787
```

## Tests / Build Results
- Backend `mvnw -B clean verify`: **407 tests, 0 failures, BUILD SUCCESS** (Testcontainers ran;
  403 on main + 4 new renderer tests).
- Frontend untouched by this branch.

## Known Risks
1. **PDF layout is a clean re-creation, not a pixel-copy of the xlsx template** (that template
   relies on merged cells/borders POI preserves). If the company requires the exact printed
   form, the next step is rendering the filled xlsx through LibreOffice headless — a deploy
   dependency decision, out of scope here.
2. `DepositNoticeRenderer`/`PayslipRenderer` layouts unchanged (they gain sanitization/wrap
   only). Deposit-notice PDF is still its own simple layout.
3. xlsx 15-item cap + printed-subtotal-vs-DB mismatch (audit finding #16) remains — template
   rework, separate decision.
4. Mock-mode "PDF" downloads still produce the HTML placeholder saved as `.html` (deliberate
   post-xlsx-removal demo behavior; audit finding, separate frontend fix if wanted).

## Exact Next Prompt
```text
Repo GL-R-ERP, branch fix/quotation-pdf-layout. Read CLAUDE.md and
docs/agent-handoffs/53_fix-quotation-pdf-layout.md. Review the diff (main..HEAD):
(1) PdfDocumentWriter wrapping/sanitization correctness (Thai hard-break loop bounds),
(2) QuotationRenderer.toPdf layout math (column x positions vs A4 printable width),
(3) confirm DepositNoticeRenderer/PayslipRenderer outputs are unchanged apart from
newline-safety (their tests still pass). Merge on the user's say-so.
```
