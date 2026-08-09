import { describe, expect, it } from 'vitest';
import {
  allLawReferencedEntries,
  AUTO_GRANTED_ROWS,
  defaultAllowanceValues,
  LAW_REF_EXEMPTIONS,
  LAW_SOURCES,
  LOR_YOR_01_ADDRESS_KEYS,
  LOR_YOR_01_SECTIONS,
  LOR_YOR_01_TICK_KEYS,
} from './taxAllowanceSchema.js';

// Guards the ล.ย.01 form's legal citations at the data layer (feat/tax-allowance-law-references).
// See taxAllowanceSchema.js's own header comment for what each LAW_SOURCES entry genuinely is and
// the caveat that makes it easy to mislabel. This file never re-verifies a URL itself -- that was
// done by hand (curl, HTTP 200) before this feature existed -- it only guards that nobody adds a
// NEW, unverified URL to the schema without doing the same, and that no field silently lost its
// citation or picked up a copy/typo'd one instead of the shared LAW_SOURCES reference.

// The exact set verified before this file was written. Do not add to this set without verifying
// the URL yourself first (curl, HTTP 200) -- see taxAllowanceSchema.js's header comment.
const VERIFIED_URLS = new Set([
  'https://www.rd.go.th/5937.html',
  'https://www.rd.go.th/62777.html',
  'https://www.rd.go.th/65908.html',
  'https://www.rd.go.th/63475.html',
  'https://www.rd.go.th/65911.html',
  'https://www.rd.go.th/fileadmin/tax_pdf/withhold/loryor01_290362.pdf',
]);

// Explicitly rejected -- see taxAllowanceSchema.js's header comment for why (stale ปีภาษี 2560
// figures behind a current tax form). A regression test, not a redundant one: this URL is exactly
// the shape of mistake ("looks tailored for the insurance group") someone could plausibly re-add.
const REJECTED_URL = 'https://www.rd.go.th/60058.html';

describe('taxAllowanceSchema — LAW_SOURCES (verified rd.go.th citations)', () => {
  it('every LAW_SOURCES entry uses a URL from the verified set', () => {
    for (const [key, source] of Object.entries(LAW_SOURCES)) {
      expect(VERIFIED_URLS.has(source.url), `LAW_SOURCES.${key}.url (${source.url}) is not in the verified set`).toBe(true);
    }
  });

  it('never cites the explicitly-rejected stale (ปีภาษี 2560) insurance page', () => {
    const urls = Object.values(LAW_SOURCES).map((source) => source.url);
    expect(urls).not.toContain(REJECTED_URL);
  });

  it('has no duplicate URLs — each entry is a genuinely distinct source', () => {
    const urls = Object.values(LAW_SOURCES).map((source) => source.url);
    expect(new Set(urls).size).toBe(urls.length);
  });

  it('every entry has a non-empty label and "what it is" description', () => {
    for (const [key, source] of Object.entries(LAW_SOURCES)) {
      expect(source.label?.length, `LAW_SOURCES.${key} needs a label`).toBeGreaterThan(0);
      expect(source.what?.length, `LAW_SOURCES.${key} needs a "what" description`).toBeGreaterThan(0);
    }
  });

  it('the two sources research flagged as year/version-stamped carry a vintage marker', () => {
    // "A reader must be able to see a source is year-stamped without clicking it" (task spec) —
    // regression-tests that yearSummary/formPdf keep a non-null `vintage` a reader can see rendered
    // (TaxAllowanceForm.jsx's references section renders it only when non-null).
    expect(LAW_SOURCES.yearSummary.vintage).toMatch(/2568/);
    expect(LAW_SOURCES.formPdf.vintage).toMatch(/2562/);
  });

  it('the มาตรา 47 entry never claims to carry a current figure — no baht amount in its label', () => {
    // The task's own worked example of the exact lie to avoid: "ข้อกำหนดของค่าลดหย่อนประกันชีวิต"
    // pointing at มาตรา 47 (implying it carries the CURRENT insurance limit, which it does not — the
    // statute text is superseded). Guards the label specifically, since the caveat living only in
    // `caveat`/comments does not stop a future edit from rewording `label` into that same lie.
    // "47" (the section number) is fine and expected; a comma-grouped figure or "บาท" is not.
    expect(LAW_SOURCES.section47.label).toBe('ประมวลรัษฎากร มาตรา 47');
    expect(LAW_SOURCES.section47.label).not.toMatch(/บาท|\d{1,3}(,\d{3})+/);
  });

  it('the RMF/SSF and Thai ESG sources never claim to be the deduction rules', () => {
    // Both are genuinely data-submission pages (task research), not explanations of the deduction
    // rule itself — "ข้อมูล...", never "หลักเกณฑ์..."/"เกณฑ์...".
    for (const key of ['rmfSsf', 'thaiEsg']) {
      expect(LAW_SOURCES[key].label).not.toMatch(/หลักเกณฑ์|เกณฑ์การลดหย่อน/);
    }
  });

  it('the FAQ source visibly discloses it is general, not per-type, in its own label', () => {
    expect(LAW_SOURCES.faq.label + LAW_SOURCES.faq.what).toMatch(/ทุกประเภท/);
  });
});

describe('taxAllowanceSchema — every allowance field cites a law source', () => {
  it('every field/row either has a lawRef pointing at a real LAW_SOURCES entry, or a written LAW_REF_EXEMPTIONS reason', () => {
    const sourceValues = new Set(Object.values(LAW_SOURCES));
    for (const entry of allLawReferencedEntries()) {
      const exemptionReason = LAW_REF_EXEMPTIONS[entry.key];
      if (exemptionReason !== undefined) {
        expect(exemptionReason.length, `LAW_REF_EXEMPTIONS["${entry.key}"] needs a written reason`).toBeGreaterThan(10);
        continue;
      }
      expect(entry.lawRef, `"${entry.key}" has neither a lawRef nor a LAW_REF_EXEMPTIONS entry`).toBeDefined();
      // Reference equality on purpose: every field should point at the SAME LAW_SOURCES object, not
      // a hand-copied/typo'd duplicate — this is what actually guards "every URL is verified"
      // end-to-end, since a copy could silently diverge from the entry it was copied from.
      expect(
        sourceValues.has(entry.lawRef),
        `"${entry.key}"'s lawRef is not one of LAW_SOURCES' own entries (looks like a copy instead of a shared reference)`,
      ).toBe(true);
    }
  });

  it('every LAW_REF_EXEMPTIONS entry is a real field key that genuinely has no lawRef', () => {
    const entries = allLawReferencedEntries();
    for (const [key, reason] of Object.entries(LAW_REF_EXEMPTIONS)) {
      const entry = entries.find((candidate) => candidate.key === key);
      expect(entry, `LAW_REF_EXEMPTIONS lists "${key}", which is not a real field/row key`).toBeDefined();
      expect(entry.lawRef, `LAW_REF_EXEMPTIONS["${key}"] has a lawRef now — delete the exemption`).toBeUndefined();
      expect(reason.length, `LAW_REF_EXEMPTIONS["${key}"] needs a written reason`).toBeGreaterThan(10);
    }
  });

  it('covers every field across the ล.ย.01 sections plus the auto-granted row', () => {
    // Not a load-bearing check on the literal number — it is a tripwire: if a field is ever added
    // to LOR_YOR_01_SECTIONS/AUTO_GRANTED_ROWS this count moves and the diff becomes visible in
    // review, instead of the new field silently having no lawRef and no exemption.
    const fromSections = LOR_YOR_01_SECTIONS.reduce((n, section) => n + section.fields.length, 0);
    expect(allLawReferencedEntries()).toHaveLength(fromSections + AUTO_GRANTED_ROWS.length);
    expect(allLawReferencedEntries()).toHaveLength(28);
  });

  it('the three ข้อ the form prints but nobody fills carry a note instead of fields', () => {
    // ข้อ 11 (LTF, abolished after 2019) and ข้อ 13 (SSO, derived from payroll) must never grow an
    // input. identity/status are structural sections whose controls are hand-rendered.
    for (const key of ['item11', 'item13']) {
      const section = LOR_YOR_01_SECTIONS.find((s) => s.key === key);
      expect(section.fields, `${key} must stay uneditable`).toHaveLength(0);
      expect(section.note?.length, `${key} must explain why it is uneditable`).toBeGreaterThan(20);
    }
  });

  it('no hint repeats a baht figure — live caps come from /caps, never from this file', () => {
    // The 2562 form prints superseded limits (ข้อ 7 says 10,000; the current one is 100,000).
    // Copying any of them into a hint is the exact defect this file's header forbids.
    for (const field of LOR_YOR_01_SECTIONS.flatMap((s) => s.fields)) {
      if (!field.hint) continue;
      expect(field.hint, `${field.key}'s hint must not hardcode a baht cap`)
        .not.toMatch(/[\d,]{4,}\s*บาท/);
    }
  });
});

// ---------------------------------------------------------------------------------------------
// Header prefill (owner decision #4, the read half).
//
// The rule under test is a single one, and getting it backwards is silent: a prefill must fill
// only the slots the employee left blank. If the master won instead, a correction the employee is
// halfway through making would be reverted under them on every re-render — the exact opposite of
// decision #4, where corrections flow employee -> master on HR approval and never back.
// ---------------------------------------------------------------------------------------------

const prefill = (overrides = {}) => ({
  taxpayerId: '1103700000011',
  firstNameTh: 'สมชาย',
  lastNameTh: 'ใจดี',
  maritalState: 'SINGLE',
  address: {
    building: 'อาคารเอ', roomNo: '1201', floor: '12', village: 'หมู่บ้านสวนหลวง',
    houseNo: '123/45', moo: '4', soi: 'ซอย 7', junction: 'แยกรัชดา', road: 'ถนนพระราม 9',
    subDistrict: 'ห้วยขวาง', district: 'ห้วยขวาง', province: 'กรุงเทพมหานคร', postalCode: '10310',
  },
  ...overrides,
});

describe('defaultAllowanceValues — ล.ย.01 header prefill', () => {
  it('seeds every one of the 13 address slots plus the 4 identity slots on a fresh form', () => {
    const values = defaultAllowanceValues(null, prefill());

    expect(values.lorYor01.taxpayerId).toBe('1103700000011');
    expect(values.lorYor01.firstNameTh).toBe('สมชาย');
    expect(values.lorYor01.lastNameTh).toBe('ใจดี');
    expect(values.lorYor01.maritalState).toBe('SINGLE');
    for (const key of LOR_YOR_01_ADDRESS_KEYS) {
      expect(values.lorYor01.address[key], `address.${key} was not prefilled`)
        .toBe(prefill().address[key]);
    }
  });

  it('never overwrites a value the declaration already carries', () => {
    const declaration = {
      lorYor01: {
        taxpayerId: '9999999999999',
        firstNameTh: 'ชื่อที่พนักงานแก้เอง',
        address: { houseNo: '77/7', province: 'เชียงใหม่' },
      },
    };

    const values = defaultAllowanceValues(declaration, prefill());

    expect(values.lorYor01.taxpayerId).toBe('9999999999999');
    expect(values.lorYor01.firstNameTh).toBe('ชื่อที่พนักงานแก้เอง');
    expect(values.lorYor01.address.houseNo).toBe('77/7');
    expect(values.lorYor01.address.province).toBe('เชียงใหม่');
    // ...and the slots the declaration left blank still get seeded, in the same object.
    expect(values.lorYor01.lastNameTh).toBe('ใจดี');
    expect(values.lorYor01.address.district).toBe('ห้วยขวาง');
  });

  it('treats an empty or whitespace-only declared value as blank, not as an answer', () => {
    // The declaration's own columns come back as '' rather than null for a slot the employee
    // cleared, so a plain `??` would read "" as an answer and suppress the prefill forever.
    const values = defaultAllowanceValues(
      { lorYor01: { taxpayerId: '', firstNameTh: '   ', address: { houseNo: '' } } },
      prefill(),
    );

    expect(values.lorYor01.taxpayerId).toBe('1103700000011');
    expect(values.lorYor01.firstNameTh).toBe('สมชาย');
    expect(values.lorYor01.address.houseNo).toBe('123/45');
  });

  it('prefills nothing at all when the page withholds the prefill (read-only / past year)', () => {
    const values = defaultAllowanceValues(null, null);

    expect(values.lorYor01.taxpayerId).toBe('');
    expect(values.lorYor01.maritalState).toBe('');
    for (const key of LOR_YOR_01_ADDRESS_KEYS) {
      expect(values.lorYor01.address[key], `address.${key} leaked into a withheld prefill`).toBe('');
    }
  });

  it('leaves ข้อ 2 and every declared amount untouched — no HR record can answer those', () => {
    // A prefill that reached spousalStatus/spouseHasIncome would be the app answering a question
    // about the tax year on the employee's behalf. Guarded here because the prefillable set is a
    // hand-maintained list and widening it is a one-word change.
    const values = defaultAllowanceValues(null, prefill({
      spousalStatus: 'MARRIED_WHOLE_YEAR',
      spouseHasIncome: true,
      childrenTotal: 3,
      rmfSellerName: 'บลจ. ทดสอบ',
    }));

    expect(values.lorYor01.spousalStatus).toBe('');
    expect(values.lorYor01.spouseHasIncome).toBe('');
    expect(values.lorYor01.childrenTotal).toBe('');
    expect(values.lorYor01.rmfSellerName).toBe('');
  });

  it('keeps the 8 ข้อ 4/6 ticks boolean — a prefilled string there fails validation silently', () => {
    const values = defaultAllowanceValues(null, prefill({ ownFatherSupported: 'yes' }));

    for (const key of LOR_YOR_01_TICK_KEYS) {
      expect(values.lorYor01[key], `${key} must stay a boolean`).toBe(false);
    }
  });
});
