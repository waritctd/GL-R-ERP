import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
// espree is ESLint's own parser (a direct dependency of `eslint`), so it reads these files exactly as `npm run lint`
// does. Tokenizing, rather than grepping, is the point: comments never become tokens, identifiers are not strings,
// and what is left is the text a user can be shown.
import * as espree from 'espree';

// Owner decision 2026-11-17 ("Yes, use งาน"): sales screens say งาน, not ดีล. Earlier slices converted the board, the
// deal page, the lifecycle dialogs and the document chain card; this guard holds the rest of the UI to it.
//
// WHAT IS SCANNED: every string literal, template-literal chunk, JSX text and JSX attribute string in src/**/*.{js,jsx}
// that is not a test, a test fixture, or a published มีอะไรใหม่ release (those describe screens as they were captured
// and are deliberately not rewritten). Comments and identifiers are not tokens of that kind, so they are never read.
//
// WHAT MAY STILL SAY ดีล: only the entries below, each with its reason. An entry is a substring of one token; it must
// still match something, so a stale entry fails the second test instead of quietly widening the allowance.

const SRC = path.dirname(fileURLToPath(import.meta.url));

const EXCLUDED = [
  /\.test\.jsx?$/, // test code: descriptions and assertions, not UI
  /^test\//, // test fixtures and helpers
  /^features\/whatsNew\/releases\//, // published release notices, out of scope by decision
];

const JAVA = (cls) => `server-mirroring: ${cls} returns this text with ดีล; backend wording is a separate decision`;

/** [file, substring of the token, reason]. */
const ALLOWED = [
  // ---- Frontend text that must stay verbatim with what the server says or parses ----
  ['features/tickets/stageCatalog.js', 'ดีลนี้เป็น', 'recipientRouteRefusal reproduces DealRoute.refusalMessage verbatim (pinned beside the server text)'],
  ['features/tickets/stageCatalog.js', 'ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน', 'same DealRoute.refusalMessage sentence'],
  ['utils/format.js', 'ผู้ออกแบบนำดีล', 'channel label embedded in DealRoute.refusalMessage ("ดีลนี้เป็นผู้ออกแบบนำดีล — …"); changing it here breaks that mirror'],
  ['features/tickets/DealHistoryPanel.jsx', '(ยกเลิกดีล|เสียงาน)', 'a parser for the note TicketService.cancel writes ("ยกเลิกดีล (CODE)"); never displayed'],
  ['features/quotations/quotationMeta.js', 'ดีลนี้มีคำขอราคาที่ยังดำเนินการอยู่', 'LIVE_PRICING_REQUEST_BLOCK_MESSAGE is word for word the 409 from DirectQuotationLocks'],
  ['features/quotations/quotationMeta.js', 'ให้สร้างใบเสนอราคาใหม่บนดีลนี้', 'REVISION_RECIPIENT_LOCKED_TAIL is one sentence with DealQuotationService#update\'s 409'],
  ['features/quotations/quotationMeta.js', 'ดีลนี้มีใบเสนอราคาที่ใช้งานอยู่ (', 'liveDirectQuotationBlockMessage is word for word the 409 from DirectQuotationLocks'],
  ['features/tickets/DealFulfilmentPanel.jsx', 'ส่งมอบครบจากหน้าดีล', 'a note sent to completeDelivery and stored by the server as activity text, not a label'],
  ['data/demoHr.js', 'ลูกค้ายืนยันคำสั่งซื้อของดีล', 'demo notification standing in for NotificationRepository text, which says ดีล'],

  // ---- mockApi.js: copies of the Java services' messages and activity notes ----
  ['api/mockApi.js', 'เฉพาะพนักงานขายเจ้าของดีลหรือผู้จัดการฝ่ายขาย', JAVA('LeadTimeChangeService')],
  ['api/mockApi.js', 'ปรับจำนวนสินค้าในรายการดีลให้ตรงกับคำขอราคา', JAVA('OrderConfirmationService')],
  ['api/mockApi.js', 'ยืนยันคำสั่งซื้อของดีลนี้แล้ว จึงเริ่มรอบราคาใหม่ไม่ได้', JAVA('PricingRequestService')],
  ['api/mockApi.js', 'ดีลไม่ได้อยู่ในสถานะ ACTIVE', JAVA('TicketService / DealPartyService / DealQuotationService')],
  ['api/mockApi.js', 'เลือกจากรายการในดีล หรือระบุรุ่น', JAVA('PricingRequestService')],
  ['api/mockApi.js', 'ดีลนี้ออกใบแจ้งรับมัดจำเลขที่', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'รายการที่เลือกไม่ได้อยู่ในดีลนี้', JAVA('ImportRequestService')],
  ['api/mockApi.js', 'ไม่พบดีลนี้', JAVA('TicketService and others')],
  ['api/mockApi.js', 'ดีลนี้มีใบเสนอราคาที่ยังใช้งานอยู่', JAVA('DirectQuotationLocks')],
  ['api/mockApi.js', 'ดีลนี้ไม่ต้องเก็บมัดจำ', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'ไม่พบรายการนี้ในดีล', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ยืนยันคำสั่งซื้อจากใบเสนอราคาอื่นแล้ว', JAVA('DealQuotationService')],
  ['api/mockApi.js', 'ผู้ออกแบบนำดีล', JAVA('DealRoute')],
  ['api/mockApi.js', 'ดีลนี้เป็น', JAVA('DealRoute')],
  ['api/mockApi.js', 'ดีลที่ระบุช่องทางไว้', JAVA('DealRoute')],
  ['api/mockApi.js', 'ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน', JAVA('DealRoute')],
  ['api/mockApi.js', 'ดีลถูกทำเครื่องหมายเสียงานแล้ว', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้อยู่ในขั้นตอน', JAVA('TicketService')],
  ['api/mockApi.js', 'อัตโนมัติจากขั้นตอนของดีล', JAVA('TicketService (the mock-mode stub echoes its phrase)')],
  ['api/mockApi.js', 'ไม่พบผู้เกี่ยวข้องในดีลนี้', JAVA('DealPartyService ("ไม่พบผู้เกี่ยวข้องในดีล")')],
  ['api/mockApi.js', 'แยกจากดีล', JAVA('DealPartyService (activity note)')],
  ['api/mockApi.js', 'ดีลนี้ยังไม่ได้ยืนยันปิดงาน', JAVA('TicketService')],
  ['api/mockApi.js', 'หรือดีลนี้ได้รับการยกเว้นมัดจำ', JAVA('TicketService')],
  ['api/mockApi.js', 'พักดีลไว้', JAVA('TicketService (activity note)')],
  ['api/mockApi.js', 'เปิดดีลใหม่', JAVA('TicketService (activity note)')],
  ['api/mockApi.js', 'ดีลนี้ไม่ได้อยู่ระหว่างดำเนินการ', JAVA('ExpectedPaymentService')],
  ['api/mockApi.js', 'ยอดนี้เกินยอดที่ดีลต้องชำระ', JAVA('TicketService')],
  ['api/mockApi.js', 'ที่มาสินค้าของดีลนี้แล้ว', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ใช้ที่มาสินค้าตามใบเสนอราคา', JAVA('TicketService')],
  ['api/mockApi.js', 'ต้องเลือกโครงการก่อนสร้างดีล', JAVA('TicketService')],
  ['api/mockApi.js', 'ต้องระบุช่องทางดีล', JAVA('TicketService')],
  ['api/mockApi.js', 'ไม่สามารถเป็นเจ้าของดีลได้', JAVA('TicketService')],
  ['api/mockApi.js', 'ยกเลิกดีล (', JAVA('TicketService (activity note)')],
  ['api/mockApi.js', 'ออกคำขอนำเข้าทั้งดีลไม่ได้แล้ว', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ติดตามการนำเข้าด้วยใบขอซื้อรายโรงงาน', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลต้องอยู่ในขั้นตอนจัดส่งก่อน', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ถูกทำเครื่องหมายเสียงานไปแล้ว', JAVA('TicketService')],
  ['api/mockApi.js', 'ดีลนี้ยังไม่ได้ถูกทำเครื่องหมายเสียงาน', JAVA('TicketService')],
  ['api/mockApi.js', 'ได้เฉพาะดีลที่ active หรือ on hold', JAVA('TicketService')],
  ['api/mockApi.js', 'ดำเนินการต่อได้เฉพาะดีลที่พักไว้', JAVA('TicketService')],
  ['api/mockApi.js', 'อัปเดตข้อมูลติดตามดีล', 'mock stand-in for a server-written activity note (TicketService writes "tracking fields updated")'],
  ['api/mockApi.js', 'อยู่ในดีลนี้ในบทบาทนี้แล้ว', JAVA('DealPartyService ("…อยู่ในดีลในบทบาทนี้แล้ว")')],
  ['api/mockApi.js', 'ดีลหลักต้องเป็นหนึ่งในผู้รับเหมาที่ได้งาน', JAVA('DealPartyService')],
  ['api/mockApi.js', 'เนื่องจากดีลนี้มีคำขอราคาอยู่', JAVA('TicketService')],
  ['api/mockApi.js', 'รายการนี้ไม่ได้เชื่อมกับดีล', JAVA('CommissionService ("…รายการค่าคอมจากดีลขาย")')],
  ['api/mockApi.js', 'รายการสินค้าไม่อยู่ในดีลนี้', JAVA('CommissionService')],
  ['api/mockApi.js', 'ดีลนี้ยังไม่ถึงขั้นตอนรับชำระเงินครบถ้วน', JAVA('CommissionService')],
  ['api/mockApi.js', 'มีรายการค่าคอมมิชชั่นสำหรับดีลนี้อยู่แล้ว', JAVA('CommissionService')],
  ['api/mockApi.js', 'ดีลนี้ไม่มีรายการที่ต้องออกใบขอซื้อ', JAVA('ImportRequestService')],
  ['api/mockApi.js', 'ทุกโรงงานในดีลนี้มีใบขอซื้อที่ยังใช้งานอยู่แล้ว', JAVA('ImportRequestService')],
  ['api/mockApi.js', 'กรุณาเลือกดีล', JAVA('ReceiptImportService')],
  ['api/mockApi.js', 'แต่ละดีลต้องมียอดมากกว่า 0', JAVA('ReceiptImportService')],
  ['api/mockApi.js', 'นัดเข้าพบในดีลนี้ไม่ได้', JAVA('VisitService')],
  ['api/mockApi.js', 'ดีลต้องได้รับการอนุมัติก่อน', JAVA('DepositNoticeService gate (mock paraphrase)')],
  ['api/mockApi.js', 'แล้ว ดีล ', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'ได้เฉพาะดีลที่อยู่ในสถานะ quotation_issued', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'ดีลนี้มีใบแจ้งหนี้ส่วนที่เหลือ', JAVA('RemainingInvoiceService')],
  ['api/mockApi.js', 'ดีลนี้มีร่างใบแจ้งหนี้ส่วนที่เหลืออยู่แล้ว', JAVA('RemainingInvoiceService')],
  ['api/mockApi.js', 'ถูกใช้ไปแล้วกับดีลอื่น', JAVA('PricingRequestService')],
  ['api/mockApi.js', 'ไม่ได้เป็นของดีล', JAVA('PricingRequestService')],
  ['api/mockApi.js', 'ดีลนี้มีใบเสนอราคาที่ลูกค้ายอมรับแล้วฉบับหนึ่ง', JAVA('CustomerQuotationService / DealQuotationService')],
  ['api/mockApi.js', 'สร้างใบแจ้งรับมัดจำได้เฉพาะดีลที่อนุมัติแล้ว', JAVA('DepositNoticeService')],
  ['api/mockApi.js', 'ไม่พบผู้รับเหมานี้ในดีล', JAVA('DealPartyService ("ไม่พบผู้รับเหมารายนี้ในดีล")')],
  ['api/mockApi.js', 'ดีลนี้มีใบเสนอราคาที่ใช้งานอยู่ (', JAVA('DirectQuotationLocks')],
  ['api/mockApi.js', 'ดีลต้นทางต้องอยู่ในสถานะ ACTIVE', JAVA('DealQuotationService / LeadTimeChangeService')],
  ['api/mockApi.js', 'ใบเสนอราคาฉบับใหม่ของดีลแล้ว', JAVA('DealQuotationService')],
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

const TEXT_TOKENS = new Set(['String', 'Template', 'JSXText', 'RegularExpression']);

/** Every text-bearing token containing ดีล, as { file, line, text }. */
function dealTokens() {
  const found = [];
  for (const abs of sourceFiles(SRC)) {
    const file = path.relative(SRC, abs).split(path.sep).join('/');
    if (EXCLUDED.some((re) => re.test(file))) continue;
    const code = fs.readFileSync(abs, 'utf8');
    if (!code.includes('ดีล')) continue;
    const tokens = espree.tokenize(code, {
      ecmaVersion: 'latest', sourceType: 'module', ecmaFeatures: { jsx: true }, loc: true,
    });
    for (const t of tokens) {
      if (TEXT_TOKENS.has(t.type) && t.value.includes('ดีล')) {
        found.push({ file, line: t.loc.start.line, text: t.value.replace(/\s+/g, ' ').trim() });
      }
    }
  }
  return found;
}

const allowedFor = (tok) => ALLOWED.find(([file, fragment]) => file === tok.file && tok.text.includes(fragment));

describe('sales wording: งาน, not ดีล', () => {
  const tokens = dealTokens();

  it('no user-visible string in src says ดีล outside the reasoned allowlist', () => {
    const offenders = tokens.filter((t) => !allowedFor(t)).map((t) => `${t.file}:${t.line} ${t.text.slice(0, 120)}`);
    expect(offenders, `${offenders.length} user-visible string(s) still say ดีล`).toEqual([]);
  });

  it('every allowlist entry still matches a string and carries a reason', () => {
    const stale = ALLOWED.filter(([file, fragment]) => !tokens.some((t) => t.file === file && t.text.includes(fragment)));
    expect(stale).toEqual([]);
    for (const [, fragment, reason] of ALLOWED) expect(reason, fragment).toMatch(/\S{10,}/);
  });

  it('the scanner sees JSX text, attribute strings and templates but not comments', () => {
    const code = '// ดีล in a comment\nconst a = <p title="ดีลA">ดีลB {`ดีลC ${x}`}</p>; /* ดีล */';
    const seen = espree.tokenize(code, { ecmaVersion: 'latest', sourceType: 'module', ecmaFeatures: { jsx: true } })
      .filter((t) => TEXT_TOKENS.has(t.type) && t.value.includes('ดีล'))
      .map((t) => t.value.replace(/["`$]|\{/g, '').trim());
    expect(seen).toEqual(['ดีลA', 'ดีลB', 'ดีลC']);
  });
});
