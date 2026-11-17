import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
// espree is ESLint's own parser (a direct dependency of `eslint`), so it reads these files exactly as `npm run lint`
// does. Tokenizing, rather than grepping, is the point: comments never become tokens, identifiers are not strings,
// and what is left is the text a user can be shown.
import * as espree from 'espree';

// Owner decision 2026-11-17 ("Yes, use งาน"): sales screens say งาน, not ดีล.
//
// WHAT IS SCANNED: every string literal, template-literal chunk, JSX text, JSX attribute string and regex literal in
// src/**/*.{js,jsx}, except tests, test fixtures and the published มีอะไรใหม่ releases (those have their own tests).
// Comments and identifiers are not tokens of that kind, so they are never read.
//
// WHAT MAY STILL SAY ดีล: only the WHOLE strings listed below, in the named file, each with its reason. A token is
// compared after normalising (quotes, the `${`/`}` of a template chunk and runs of whitespace removed). A new string
// that merely CONTAINS an allowed one is not allowed. A listed string that no longer exists fails the second test.

const SRC = path.dirname(fileURLToPath(import.meta.url));

const EXCLUDED = [
  /\.test\.jsx?$/, // test code: descriptions and assertions, not UI
  /^test\//, // test fixtures and helpers
  /^features\/whatsNew\/releases\//, // published release notices: 2026-11-17.test.js / releases.test.js pin those
];

// One wording per concept on sales screens (fix round 1). These spellings of an already-named concept are wrong in any
// UI string: the deal page is หน้ารายละเอียดงาน (หน้างาน is the job SITE), the link to it is เปิดงาน, its number is
// เลขที่งาน, and a split is งานหลัก / งานแยก (ดีลลูก is already caught as ดีล; "งานลูก" cannot be banned as a substring
// because โรงงานลูกค้า contains it).
const BANNED_CONCEPT_SPELLINGS = ['ไปที่งาน', 'รหัสงาน', 'Ticket ID', 'ที่หน้างาน'];

const JAVA = (cls) => `server-mirroring: ${cls} returns this text with ดีล`;

/** [file, the whole normalised string, reason]. */
const ALLOWED = [
  ['data/demoHr.js', 'ลูกค้ายืนยันคำสั่งซื้อของดีล PR-2026-0007 แล้ว', 'demo notification standing in for NotificationRepository text, which says ดีล'],
  ['features/quotations/quotationMeta.js', 'ดีลนี้มีคำขอราคาที่ยังดำเนินการอยู่ — ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน', 'LIVE_PRICING_REQUEST_BLOCK_MESSAGE is word for word the 409 from DirectQuotationLocks'],
  ['features/quotations/quotationMeta.js', 'หากจะเสนอราคาให้ผู้รับรายอื่น ให้สร้างใบเสนอราคาใหม่บนดีลนี้', 'REVISION_RECIPIENT_LOCKED_TAIL is one sentence with DealQuotationService#update\'s 409'],
  ['features/quotations/quotationMeta.js', 'ดีลนี้มีใบเสนอราคาที่ใช้งานอยู่ (', 'liveDirectQuotationBlockMessage is word for word the 409 from DirectQuotationLocks'],
  ['features/tickets/DealFulfilmentPanel.jsx', 'ส่งมอบครบจากหน้าดีล', 'a note sent to completeDelivery and stored by the server as activity text, not a label'],
  ['features/tickets/DealHistoryPanel.jsx', '/^(ยกเลิกดีล|เสียงาน) \\(([A-Z_]+)\\)(?: — (.*))?$/s', 'a parser for the note TicketService.cancel writes ("ยกเลิกดีล (CODE)"); never displayed'],
  ['features/tickets/stageCatalog.js', 'ดีลนี้เป็น', 'recipientRouteRefusal reproduces DealRoute.refusalMessage verbatim (pinned beside the server text)'],
  ['features/tickets/stageCatalog.js', 'ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน', 'same DealRoute.refusalMessage sentence'],
  ['utils/format.js', 'ผู้ออกแบบนำดีล', 'channel label embedded in DealRoute.refusalMessage ("ดีลนี้เป็นผู้ออกแบบนำดีล — …"); changing it here breaks that mirror'],
  // ---- mockApi.js: copies of the Java services' messages and activity notes ----
  ['api/mockApi.js', 'เฉพาะพนักงานขายเจ้าของดีลหรือผู้จัดการฝ่ายขายเท่านั้นที่อนุมัติหรือไม่อนุมัติได้', JAVA('LeadTimeChangeService')],
  ['api/mockApi.js', 'ปรับจำนวนสินค้าในรายการดีลให้ตรงกับคำขอราคาที่ยืนยันคำสั่งซื้อแล้ว', JAVA('OrderConfirmationService')],
  ['api/mockApi.js', 'ยืนยันคำสั่งซื้อของดีลนี้แล้ว จึงเริ่มรอบราคาใหม่ไม่ได้ — การเปลี่ยนแปลงหลังยืนยันต้องทำเป็นการแก้ไขคำสั่งซื้อ', JAVA('PricingRequestService')],
  ['api/mockApi.js', 'ดีลไม่ได้อยู่ในสถานะ ACTIVE (', JAVA('TicketService / DealPartyService / DealQuotationService')],
  ['api/mockApi.js', ': ต้องระบุสินค้าที่ต้องการเสนอราคา (เลือกจากรายการในดีล หรือระบุรุ่น/รายละเอียด)', JAVA('PricingRequestService')],
  ['api/mockApi.js', 'ดีลนี้ออกใบแจ้งรับมัดจำเลขที่', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'รายการที่เลือกไม่ได้อยู่ในดีลนี้', JAVA('ImportRequestService')],
  ['api/mockApi.js', 'ไม่พบดีลนี้', JAVA('TicketService and others')],
  ['api/mockApi.js', 'ดีลนี้มีใบเสนอราคาที่ยังใช้งานอยู่ — แก้ไขรายการที่ใบเสนอราคา หรือยกเลิกใบเสนอราคาก่อน', JAVA('DirectQuotationLocks')],
  ['api/mockApi.js', 'ดีลนี้ไม่ต้องเก็บมัดจำ (นโยบายมัดจำ', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'ไม่พบรายการนี้ในดีล', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ยืนยันคำสั่งซื้อจากใบเสนอราคาอื่นแล้ว จึงส่ง/อนุมัติใบเสนอราคานี้ไม่ได้', JAVA('DealQuotationService')],
  ['api/mockApi.js', 'ผู้ออกแบบนำดีล', JAVA('DealRoute')],
  ['api/mockApi.js', 'ดีลนี้เป็น', JAVA('DealRoute')],
  ['api/mockApi.js', 'ดีลที่ระบุช่องทางไว้', JAVA('DealRoute')],
  ['api/mockApi.js', 'ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน', JAVA('DealRoute')],
  ['api/mockApi.js', 'ดีลถูกทำเครื่องหมายเสียงานแล้ว — เปิดดีลใหม่ก่อนแก้ไขสถานะ', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้อยู่ในขั้นตอน', JAVA('TicketService')],
  ['api/mockApi.js', 'ไม่ได้: ขั้นตอนนี้อัปเดตอัตโนมัติจากขั้นตอนของดีล', JAVA('TicketService (the mock-mode stub echoes its phrase)')],
  ['api/mockApi.js', 'อัตโนมัติจากขั้นตอนของดีล', JAVA('TicketService (the mock-mode stub echoes its phrase)')],
  ['api/mockApi.js', 'ไม่พบผู้เกี่ยวข้องในดีลนี้', JAVA('DealPartyService ("ไม่พบผู้เกี่ยวข้องในดีล")')],
  ['api/mockApi.js', 'แยกจากดีล', JAVA('DealPartyService (activity note)')],
  ['api/mockApi.js', 'ดีลนี้ยังไม่ได้ยืนยันปิดงาน', JAVA('TicketService')],
  ['api/mockApi.js', 'ต้องรับชำระมัดจำแล้วหรือรอชำระเงินงวดสุดท้าย (หรือดีลนี้ได้รับการยกเว้นมัดจำ) และดีลต้องถึงขั้นส่งมอบ (รอสินค้า/นำเข้าเสร็จสิ้น) ก่อนจึงจะยืนยันรับชำระเงินครบถ้วนได้', JAVA('TicketService')],
  ['api/mockApi.js', 'พักดีลไว้', JAVA('TicketService (activity note)')],
  ['api/mockApi.js', 'เปิดดีลใหม่', JAVA('TicketService (activity note)')],
  ['api/mockApi.js', 'ดีลนี้ไม่ได้อยู่ระหว่างดำเนินการ จึงแก้ไขเงินที่คาดว่าจะเข้าไม่ได้', JAVA('ExpectedPaymentService')],
  ['api/mockApi.js', 'ดีลไม่ได้อยู่ในสถานะ ACTIVE จึงบันทึกรับเงินไม่ได้', JAVA('TicketService / DealPartyService / DealQuotationService')],
  ['api/mockApi.js', 'ยอดนี้เกินยอดที่ดีลต้องชำระ ให้ฝ่ายบัญชีตรวจสอบ', JAVA('TicketService')],
  ['api/mockApi.js', 'เลือกที่มาสินค้าของดีลนี้แล้ว แก้ไขไม่ได้', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ใช้ที่มาสินค้าตามใบเสนอราคา แก้ไขไม่ได้', JAVA('TicketService')],
  ['api/mockApi.js', 'ต้องเลือกโครงการก่อนสร้างดีล', JAVA('TicketService')],
  ['api/mockApi.js', 'ต้องระบุช่องทางดีลก่อนสร้างดีล', JAVA('TicketService')],
  ['api/mockApi.js', 'ต้องระบุช่องทางดีลที่แท้จริง (ออกแบบนำ / เจ้าของโครงการ / ผู้ซื้อตรง) ก่อนสร้างดีล', JAVA('TicketService')],
  ['api/mockApi.js', 'เจ้าของงานที่เลือกไม่สามารถเป็นเจ้าของดีลได้', JAVA('TicketService')],
  ['api/mockApi.js', 'ยกเลิกดีล (', JAVA('TicketService (activity note)')],
  ['api/mockApi.js', 'ออกคำขอนำเข้าทั้งดีลไม่ได้แล้ว — ให้ฝ่ายขายสร้างและออกเลขใบขอซื้อรายโรงงานแทน', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ติดตามการนำเข้าด้วยใบขอซื้อรายโรงงาน — ต้องบันทึกความคืบหน้าที่ใบขอซื้อของแต่ละโรงงาน', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลต้องอยู่ในขั้นตอนจัดส่งก่อนจึงจะทำเครื่องหมายว่าได้รับสินค้าได้', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ถูกทำเครื่องหมายเสียงานไปแล้ว', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ยังไม่ได้ถูกทำเครื่องหมายเสียงาน', JAVA('TicketService')],
  ['api/mockApi.js', 'พัก dormant ได้เฉพาะดีลที่ active หรือ on hold', JAVA('TicketService')],
  ['api/mockApi.js', 'ดำเนินการต่อได้เฉพาะดีลที่พักไว้', JAVA('TicketService')],
  ['api/mockApi.js', 'อัปเดตข้อมูลติดตามดีล', 'mock stand-in for a server-written activity note (TicketService writes "tracking fields updated")'],
  ['api/mockApi.js', 'อยู่ในดีลนี้ในบทบาทนี้แล้ว', JAVA('DealPartyService ("…อยู่ในดีลในบทบาทนี้แล้ว")')],
  ['api/mockApi.js', 'ดีลหลักต้องเป็นหนึ่งในผู้รับเหมาที่ได้งาน', JAVA('DealPartyService')],
  ['api/mockApi.js', 'เปลี่ยนลูกค้าไม่ได้ เนื่องจากดีลนี้มีคำขอราคาอยู่', JAVA('TicketService')],
  ['api/mockApi.js', 'รายการนี้ไม่ได้เชื่อมกับดีล', JAVA('CommissionService ("…รายการค่าคอมจากดีลขาย")')],
  ['api/mockApi.js', 'รายการสินค้าไม่อยู่ในดีลนี้', JAVA('CommissionService')],
  ['api/mockApi.js', 'ดีลนี้ยังไม่ถึงขั้นตอนรับชำระเงินครบถ้วน (CLOSED_PAID) จึงยังยื่นค่าคอมมิชชั่นไม่ได้', JAVA('CommissionService')],
  ['api/mockApi.js', 'มีรายการค่าคอมมิชชั่นสำหรับดีลนี้อยู่แล้ว', JAVA('CommissionService')],
  ['api/mockApi.js', 'ดีลนี้ไม่มีรายการที่ต้องออกใบขอซื้อ (ยังไม่มีรายการสินค้า หรือครอบคลุมจากสต็อกทั้งหมด)', JAVA('ImportRequestService')],
  ['api/mockApi.js', 'ทุกโรงงานในดีลนี้มีใบขอซื้อที่ยังใช้งานอยู่แล้ว', JAVA('ImportRequestService')],
  ['api/mockApi.js', 'กรุณาเลือกดีล', JAVA('ReceiptImportService')],
  ['api/mockApi.js', 'แต่ละดีลต้องมียอดมากกว่า 0 และไม่ซ้ำกัน', JAVA('ReceiptImportService')],
  ['api/mockApi.js', 'ดีลนี้ไม่ได้อยู่ระหว่างดำเนินการ จึงเพิ่มเงินที่คาดว่าจะเข้าไม่ได้', JAVA('ExpectedPaymentService')],
  ['api/mockApi.js', 'ดีลนี้ไม่ได้เปิดอยู่ จึงนัดเข้าพบในดีลนี้ไม่ได้', JAVA('VisitService')],
  ['api/mockApi.js', 'ดีลต้องได้รับการอนุมัติก่อน', JAVA('DepositNoticeService gate (mock paraphrase)')],
  ['api/mockApi.js', 'แล้ว ดีล', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'ออกใบกำกับภาษีส่วนที่เหลือได้เฉพาะดีลที่อยู่ในสถานะ quotation_issued เท่านั้น', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'ดีลนี้มีใบแจ้งหนี้ส่วนที่เหลือ (ร่างหรือออกแล้ว) สำหรับใบเสนอราคานี้อยู่แล้ว', JAVA('RemainingInvoiceService')],
  ['api/mockApi.js', 'ดีลนี้มีร่างใบแจ้งหนี้ส่วนที่เหลืออยู่แล้ว (ใบเสนอราคาอื่น) กรุณาออกหรือลบร่างเดิมก่อน', JAVA('RemainingInvoiceService')],
  ['api/mockApi.js', 'clientRequestId นี้ถูกใช้ไปแล้วกับดีลอื่น', JAVA('PricingRequestService')],
  ['api/mockApi.js', 'ไม่ได้เป็นของดีล', JAVA('PricingRequestService')],
  ['api/mockApi.js', 'ดีลนี้มีใบเสนอราคาที่ลูกค้ายอมรับแล้วฉบับหนึ่ง — ยอมรับซ้ำอีกฉบับไม่ได้ (R8)', JAVA('CustomerQuotationService / DealQuotationService')],
  ['api/mockApi.js', 'สร้างใบแจ้งรับมัดจำได้เฉพาะดีลที่อนุมัติแล้วเท่านั้น', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'ไม่พบผู้รับเหมานี้ในดีล', JAVA('DealPartyService ("ไม่พบผู้รับเหมารายนี้ในดีล")')],
  ['api/mockApi.js', 'ดีลนี้มีใบเสนอราคาที่ใช้งานอยู่ (', JAVA('DirectQuotationLocks')],
  ['api/mockApi.js', 'ดีลต้นทางต้องอยู่ในสถานะ ACTIVE', JAVA('DealQuotationService / LeadTimeChangeService')],
  ['api/mockApi.js', 'คำขอราคานี้ถูกแทนที่ด้วยใบเสนอราคาฉบับใหม่ของดีลแล้ว — ออกใบเสนอราคาจากคำขอราคาล่าสุด หรือสร้างคำขอราคาใหม่', JAVA('DealQuotationService')],
  ['api/mockApi.js', 'ยืนยันคำสั่งซื้อไม่ได้ — สถานะดีล (', JAVA('DealQuotationService')],
];

function sourceFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) sourceFiles(p, out);
    else if (/\.jsx?$/.test(entry.name)) out.push(p);
  }
  return out;
}

/** Every scanned file, relative to src with forward slashes. */
function scannedFiles() {
  return sourceFiles(SRC)
    .map((abs) => path.relative(SRC, abs).split(path.sep).join('/'))
    .filter((file) => !EXCLUDED.some((re) => re.test(file)));
}

const TEXT_TOKENS = new Set(['String', 'Template', 'JSXText', 'RegularExpression']);

/** A token's text as a user would read it: no quotes, no template punctuation, whitespace collapsed. */
function normalise(token) {
  let v = token.value;
  if (token.type === 'String' || /^(["']).*\1$/s.test(v)) v = v.slice(1, -1); // a JSX attribute string too
  else if (token.type === 'Template') v = v.replace(/^[`}]/, '').replace(/(\$\{|`)$/, '');
  return v.replace(/\s+/g, ' ').trim();
}

/** Every text-bearing token in `code`, as { file, line, text }. */
function textTokens(code, file) {
  return espree.tokenize(code, { ecmaVersion: 'latest', sourceType: 'module', ecmaFeatures: { jsx: true }, loc: true })
    .filter((t) => TEXT_TOKENS.has(t.type))
    .map((t) => ({ file, line: t.loc.start.line, text: normalise(t) }));
}

function scan() {
  return scannedFiles().flatMap((file) => textTokens(fs.readFileSync(path.join(SRC, file), 'utf8'), file));
}

const allowedFor = (tok) => ALLOWED.find(([file, text]) => file === tok.file && text === tok.text);

describe('sales wording: งาน, not ดีล', () => {
  const tokens = scan();
  const deal = tokens.filter((t) => t.text.includes('ดีล'));

  it('no user-visible string in src says ดีล outside the reasoned allowlist', () => {
    const offenders = deal.filter((t) => !allowedFor(t)).map((t) => `${t.file}:${t.line} ${t.text.slice(0, 120)}`);
    expect(offenders, `${offenders.length} user-visible string(s) still say ดีล`).toEqual([]);
  });

  it('every allowlist entry still matches a whole string and carries a reason', () => {
    const stale = ALLOWED.filter(([file, text]) => !deal.some((t) => t.file === file && t.text === text));
    expect(stale).toEqual([]);
    for (const [, text, reason] of ALLOWED) expect(reason, text).toMatch(/\S{10,}/);
  });

  it('one wording per concept: no UI string uses a retired spelling', () => {
    const offenders = tokens
      .filter((t) => BANNED_CONCEPT_SPELLINGS.some((b) => t.text.includes(b)))
      .map((t) => `${t.file}:${t.line} ${t.text.slice(0, 120)}`);
    expect(offenders).toEqual([]);
  });

  it('the scanner reads JSX text, attributes and templates, skips comments, and covers all of src but the 3 exclusions', () => {
    const code = '// ดีล in a comment\nconst a = <p title="ดีลA" aria-label={\'ดีลH\'}>ดีลB {`ดีลC ${x} ดีลI`}</p>; /* ดีล */';
    expect(textTokens(code, 'x.jsx').map((t) => t.text).filter((s) => s.includes('ดีล')))
      .toEqual(['ดีลA', 'ดีลH', 'ดีลB', 'ดีลC', 'ดีลI']);
    // Widening an exclusion must fail here, not silently turn the guard green.
    expect(EXCLUDED.map(String)).toEqual([
      String(/\.test\.jsx?$/), String(/^test\//), String(/^features\/whatsNew\/releases\//),
    ]);
    const files = scannedFiles();
    for (const sentinel of [
      'api/mockApi.js', 'data/demoHr.js', 'utils/format.js', 'app/permissions.js',
      'features/tickets/TicketDetailPage.jsx', 'features/quotations/QuotationEditorPage.jsx',
      'features/salesBoard/TeamOverview.jsx', 'features/whatsNew/feedbackMeta.js',
    ]) {
      expect(files, sentinel).toContain(sentinel);
    }
    const all = sourceFiles(SRC).length;
    const tests = sourceFiles(SRC).filter((f) => /\.test\.jsx?$/.test(f)).length;
    expect(files.length).toBeGreaterThan((all - tests) * 0.9);
  });
});
