import { describe, expect, it } from 'vitest';
import {
  buildAllowanceSubmitBody,
  defaultAllowanceValues,
  emptyAllowanceValues,
  LAW_SOURCES,
} from './taxAllowanceSchema.js';

// The exact set verified before this file was written. Do not add to this set without verifying the URL
// yourself first (curl, HTTP 200) -- see taxAllowanceSchema.js's header comment.
const VERIFIED_URLS = new Set([
  'https://www.rd.go.th/5937.html',
  'https://www.rd.go.th/62777.html',
  'https://www.rd.go.th/65908.html',
  'https://www.rd.go.th/63475.html',
  'https://www.rd.go.th/65911.html',
  'https://www.rd.go.th/fileadmin/tax_pdf/withhold/loryor01_290362.pdf',
]);

describe('taxAllowanceSchema — LAW_SOURCES (verified rd.go.th citations)', () => {
  it('every LAW_SOURCES entry uses a URL from the verified set', () => {
    for (const [key, source] of Object.entries(LAW_SOURCES)) {
      expect(VERIFIED_URLS.has(source.url), `LAW_SOURCES.${key}.url (${source.url}) is not in the verified set`).toBe(true);
    }
  });
});

// Header prefill (owner decision #4, the read half). The rule under test is a single one, and getting it
// backwards is silent: a prefill must fill only the slots the employee left blank.

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
});

describe('buildAllowanceSubmitBody — whole tax year, no effective month', () => {
  // The wire-shape half of the 2026-08-31 ruling. TaxAllowanceDeclarationSubmitRequest and
  // TaxAllowanceOnBehalfRequest both dropped `effectiveMonth`, and Jackson ignores unknown properties by default
  // — so a body that still carried one would be accepted, silently discarded, and look like it worked.
  it('omits effectiveMonth entirely from the submitted body', () => {
    const body = buildAllowanceSubmitBody(emptyAllowanceValues(), { taxYear: 2026 });

    expect(Object.keys(body)).not.toContain('effectiveMonth');
    expect(body.taxYear).toBe(2026);
  });
});
