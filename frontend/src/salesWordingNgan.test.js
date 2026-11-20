import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
// Owner decision 2026-11-17: sales screens say งาน, not ดีล; scans every user-visible string in src.
// espree is ESLint's own parser (a direct dependency of `eslint`), so it reads these files exactly as `npm run lint`
// does. Tokenizing, rather than grepping, is the point: comments never become tokens, identifiers are not strings,
// and what is left is the text a user can be shown.
import * as espree from 'espree';

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

/** [file, the whole normalised string, reason]. Server text the UI shows now says งาน too (fix round 1, commit B);
 * what is left is text that is stored, parsed or matched elsewhere, or that the server still sends by email. */
const ALLOWED = [
  ['features/tickets/DealHistoryPanel.jsx', '/^(ยกเลิกดีล|เสียงาน) \\(([A-Z_]+)\\)(?: — (.*))?$/s',
    'parses the note TicketService.cancel writes ("ยกเลิกดีล (CODE)"); that note stays because this parser reads it, and existing rows say it'],
  ['api/mockApi.js', 'ยกเลิกดีล (',
    'mock copy of TicketService.cancel\'s stored note, which DealHistoryPanel parses (see above)'],
  ['api/mockApi.js', 'อัตโนมัติจากขั้นตอนของดีล',
    'mock copy of TicketService\'s stage note; migration V210 matches that exact text (e.message IS DISTINCT FROM …), so Java keeps it'],
  ['api/mockApi.js', 'ไม่ได้: ขั้นตอนนี้อัปเดตอัตโนมัติจากขั้นตอนของดีล',
    'the mock-mode stub ends with the same V210-matched phrase as the note above'],
  ['api/mockApi.js', 'แล้ว ดีล',
    'mock copy of DepositNoticeService\'s DEPOSIT_NOTICE_ISSUED bell text; Java keeps it because the same text is emailed'],
  ['data/demoHr.js', 'ลูกค้ายืนยันคำสั่งซื้อของดีล PR-2026-0007 แล้ว',
    'demo bell row standing in for notification text, which Java keeps because the same text is emailed'],
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

  it('one wording per concept: no UI string uses a retired spelling', () => {
    const offenders = tokens
      .filter((t) => BANNED_CONCEPT_SPELLINGS.some((b) => t.text.includes(b)))
      .map((t) => `${t.file}:${t.line} ${t.text.slice(0, 120)}`);
    expect(offenders).toEqual([]);
  });
});
