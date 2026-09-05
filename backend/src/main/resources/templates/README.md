# Sales document templates

`quotation_template.xls`, `deposit_notice_template.xls`, `remaining_invoice_template.xls`
are the real GL&R company forms (ใบเสนอราคา / ใบมัดจำ / ใบแจ้งหนี้), authored in legacy
BIFF8 `.xls` — on purpose, not a migration-in-progress.

`QuotationRenderer`, `DepositNoticeRenderer` and `RemainingInvoiceRenderer` open them with
`WorkbookFactory.create(...)`, which returns an `HSSFWorkbook` for a BIFF8 stream. Every
`wb.write()` these renderers produce is therefore `.xls` bytes, no matter what a caller
names the file.

**Do not convert these templates to `.xlsx` (OOXML/XSSF).** It would break the pixel-exact
official forms — cell styles and print areas are tuned against this BIFF8 layout, using the
fonts documented in `backend/fonts/README.md` — and break the LibreOffice PDF path, which
converts this same workbook (`toPdf()` calls `toXlsx()`, then shells out to `soffice`).

Since PR #891, the download endpoints advertise this honestly: `.xls` filename +
`application/vnd.ms-excel` Content-Type. The `?format=xlsx` request parameter is unchanged
(it picks the spreadsheet branch over `?format=pdf`, not a file format) — only the response
labeling changed; the bytes were always BIFF8.

Three `.xlsx` files with these same basenames used to sit in this folder — orphaned
duplicates left over from before PR #258 rebound the renderers onto these real `.xls`
templates. Nothing read them; they were deleted as dead weight.
