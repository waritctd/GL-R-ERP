import { describe, it, expect } from 'vitest';
import { api, retireLiveDirectQuotationsForTests, retireLivePricingRequestsForTests } from './mockApi.js';

// V192.3 — ผู้ติดต่อ's Phone and Email (`orderedByPhone` / `orderedByEmail`). Mirrors
// DealQuotationService#create/#update/#insertCopyOf's handling of the two fields.
//
// PLUMBING evidence only: the fields survive the mock's own create / get / update / revision /
// reorder paths, and the mock refuses what the service refuses, in the same words. That the real
// service stores, refuses and never prints them is proven against Java and a real Postgres
// (DealQuotationOrderedByContactIntegrationTest), never here.

const salesUser = { role: 'sales' }; // id 6, owns demo ticket 18
// Slice 2: a direct create needs a recipient (S2-B1) and refuses a deal that already holds a live
// direct quotation (S2-B3, N6) or a live pricing request. Ticket 18's seed holds both, so this
// retires them first -- the state a rep reaches by cancelling -- exactly as mockApi.dealQuotations.test.js does.
const createOn18 = (payload) => {
  retireLiveDirectQuotationsForTests(18);
  retireLivePricingRequestsForTests(18);
  return api.dealQuotations.create(18, { recipientType: 'DESIGNER', ...payload });
};

// The smallest body the real service accepts (see mockApi.dealQuotations.test.js's own ONE_ITEM).
const ONE_ITEM = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0,
  leadTimeMinDays: 30, leadTimeMaxDays: 45,
};

// A name typed into the request's orderedByName: ignored since the 2026-10-18 ruling -- the
// signature name is the picked contact's (PICKED_NAME, contact 7 of ticket 18's customer).
const NAME = 'คุณวิชัย มั่นคง';
const PICKED = { contactId: 7 };
const PICKED_NAME = 'พิมพ์ใจ บุญมาก';
const PHONE = '089-765-4321 ต่อ 77';
const EMAIL = 'bid.contact@contractor-b.example';
const contact = (fields = {}) => ({
  orderedByName: NAME, orderedByPhone: PHONE, orderedByEmail: EMAIL, items: [ONE_ITEM], ...fields,
});

const MALFORMED_EMAILS = [
  'somchai.example.com', 'somchai@', '@example.com', 'somchai@example', 'som chai@example.com',
  'somchai@example@gmail.com',
];

describe('mock dealQuotations — ผู้ติดต่อ Phone / Email (V192.3)', () => {
  it('create stores both beside the name, and get returns them', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18(contact(PICKED));
    expect(created).toMatchObject({ orderedByName: PICKED_NAME, orderedByPhone: PHONE, orderedByEmail: EMAIL });

    const { quotation: reread } = await api.dealQuotations.get(created.id);
    expect(reread).toMatchObject({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
  });

  it('a quotation created without them serves both as null — the keys are always on the DTO', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ items: [ONE_ITEM] });
    expect(quotation).toMatchObject({ orderedByPhone: null, orderedByEmail: null });
  });

  it('create trims both, and stores a blank one as null', async () => {
    await api.auth.login(salesUser);
    const { quotation: padded } = await createOn18(contact({ orderedByPhone: `  ${PHONE} `, orderedByEmail: ` ${EMAIL}  ` }));
    expect(padded).toMatchObject({ orderedByPhone: PHONE, orderedByEmail: EMAIL });

    const { quotation: blank } = await createOn18(contact({ orderedByPhone: '   ', orderedByEmail: '' }));
    expect(blank).toMatchObject({ orderedByPhone: null, orderedByEmail: null });
  });

  it('update replaces both', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18(contact());
    const { quotation: updated } = await api.dealQuotations.update(created.id, contact({
      orderedByPhone: '02-123-4567', orderedByEmail: 'purchasing@contractor-b.example',
    }));
    expect(updated).toMatchObject({ orderedByPhone: '02-123-4567', orderedByEmail: 'purchasing@contractor-b.example' });
  });

  // The editor states every header field on every save, so a blank phone or email — or one it does
  // not send at all — clears it. The name stays: it is the picked contact's, kept by a save that
  // sends no contactId (a typed one is ignored).
  it('an update that blanks or omits them clears them, and leaves the name alone', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18(contact(PICKED));
    expect(created).toMatchObject({ orderedByPhone: PHONE, orderedByEmail: EMAIL }); // there is something to clear

    const { quotation: updated } = await api.dealQuotations.update(created.id, {
      orderedByName: NAME, orderedByEmail: '  ', items: [ONE_ITEM],
    });
    expect(updated).toMatchObject({ orderedByName: PICKED_NAME, orderedByPhone: null, orderedByEmail: null });
  });

  it('refuses a malformed email on create and on update, in the service\'s own words, and keeps what was stored', async () => {
    await api.auth.login(salesUser);
    for (const malformed of MALFORMED_EMAILS) {
      await expect(createOn18(contact({ orderedByEmail: malformed })), malformed)
        .rejects.toMatchObject({ status: 400, message: 'Email ของลูกค้า: รูปแบบอีเมลไม่ถูกต้อง' });
    }

    const { quotation: created } = await createOn18(contact());
    await expect(api.dealQuotations.update(created.id, contact({ orderedByPhone: '02-123-4567', orderedByEmail: 'somchai@' })))
      .rejects.toMatchObject({ status: 400 });
    const { quotation: reread } = await api.dealQuotations.get(created.id);
    expect(reread).toMatchObject({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
  });

  it('refuses an email over 254 characters and a phone over 50, and accepts each at its limit', async () => {
    await api.auth.login(salesUser);
    const longestEmail = `${'a'.repeat(254 - '@example.com'.length)}@example.com`;
    const longestPhone = '0'.repeat(50);

    await expect(createOn18(contact({ orderedByEmail: `a${longestEmail}` })))
      .rejects.toMatchObject({ status: 400, message: 'Email ของลูกค้ายาวเกิน 254 ตัวอักษร' });
    await expect(createOn18(contact({ orderedByPhone: `${longestPhone}1` })))
      .rejects.toMatchObject({ status: 400, message: 'Phone ของลูกค้ายาวเกิน 50 ตัวอักษร' });

    const { quotation } = await createOn18(contact({ orderedByEmail: longestEmail, orderedByPhone: longestPhone }));
    expect(quotation).toMatchObject({ orderedByEmail: longestEmail, orderedByPhone: longestPhone });
  });

  it('createRevision and createReorder both carry them', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18(contact());
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: approved } = await api.dealQuotations.approve(created.id, {});
    await api.auth.login(salesUser);

    const { quotation: reorder } = await api.dealQuotations.createReorder(approved.id, {});
    expect(reorder).toMatchObject({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
    const { quotation: revision } = await api.dealQuotations.createRevision(approved.id);
    expect(revision).toMatchObject({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
  });
});
