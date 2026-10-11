import { describe, expect, it } from 'vitest';
import { api } from './mockApi.js';

// เอกสารแนบ of a quotation in the MOCK. Mirrors DealQuotationAttachmentController +
// DealQuotationAttachmentService.
//
// PLUMBING evidence only. The four methods exist, keep what they are given, answer the envelopes
// the real controller answers, and refuse in the service's own status codes so the panel can be
// driven under VITE_USE_MOCKS=true. It is NOT evidence about who the real service refuses. A mock
// more permissive than production is how #199 happened. That rule is proven against Java and a
// real Postgres in DealQuotationAttachmentAuthzIntegrationTest, never here.

const sales = { role: 'sales' }; // id 6, owns demo ticket 18

// The smallest body the real service accepts (see mockApi.dealQuotations.test.js's own ONE_ITEM).
const ONE_ITEM = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0,
  leadTimeMinDays: 30, leadTimeMaxDays: 45,
};

const draftOn18 = async () => (await api.dealQuotations.create(18, { items: [ONE_ITEM] })).quotation;
const pdf = (name = 'vendor-quote.pdf') => new File(['%PDF-1.4 vendor cost'], name, { type: 'application/pdf' });
const ids = (answer) => answer.items.map((item) => item.id);

/** Fails on an assertion, not a TypeError, while a method is still missing. */
function expectTheFourMethods() {
  for (const name of ['listAttachments', 'uploadAttachment', 'deleteAttachment', 'attachmentUrl']) {
    expect(api.dealQuotations[name], `mockApi.dealQuotations.${name}`).toBeTypeOf('function');
  }
}

describe('mock dealQuotations, เอกสารแนบ', () => {
  it('upload keeps the file, list returns it, and delete takes it away again', async () => {
    expectTheFourMethods();
    await api.auth.login(sales);
    const quotation = await draftOn18();

    const { attachment } = await api.dealQuotations.uploadAttachment(quotation.id, pdf('ใบเสนอราคา ผู้ขาย.pdf'));

    expect(attachment).toMatchObject({
      quotationId: quotation.id, quotationNumber: quotation.number, fileName: 'ใบเสนอราคา ผู้ขาย.pdf',
      mimeType: 'application/pdf', fileSize: pdf().size, uploadedById: 6, inherited: false,
    });
    expect(typeof attachment.id).toBe('number');
    expect(attachment.uploadedByName).toBeTruthy();
    expect(Number.isNaN(Date.parse(attachment.uploadedAt))).toBe(false);
    expect(await api.dealQuotations.listAttachments(quotation.id)).toEqual({ items: [attachment] });
    expect(api.dealQuotations.attachmentUrl(attachment.id)).toMatch(/\S/);

    expect(await api.dealQuotations.deleteAttachment(attachment.id)).toEqual({ ok: true });
    expect(await api.dealQuotations.listAttachments(quotation.id)).toEqual({ items: [] });
  });

  it('refuses account and import the list and the upload, and the CEO the upload', async () => {
    expectTheFourMethods();
    await api.auth.login(sales);
    const quotation = await draftOn18();
    const { attachment } = await api.dealQuotations.uploadAttachment(quotation.id, pdf());

    for (const role of ['account', 'import']) {
      await api.auth.login({ role });
      // Control: this role does open the quotation itself in the mock, as on main.
      expect((await api.dealQuotations.get(quotation.id)).quotation.id, role).toBe(quotation.id);
      await expect(api.dealQuotations.listAttachments(quotation.id), role).rejects.toMatchObject({ status: 403 });
      await expect(api.dealQuotations.uploadAttachment(quotation.id, pdf()), role).rejects.toMatchObject({ status: 403 });
      await expect(api.dealQuotations.deleteAttachment(attachment.id), role).rejects.toMatchObject({ status: 403 });
    }

    await api.auth.login({ role: 'ceo' });
    expect(ids(await api.dealQuotations.listAttachments(quotation.id))).toEqual([attachment.id]);
    await expect(api.dealQuotations.uploadAttachment(quotation.id, pdf())).rejects.toMatchObject({ status: 403 });
    await expect(api.dealQuotations.deleteAttachment(attachment.id)).rejects.toMatchObject({ status: 403 });
  });

  it('takes files while DRAFT and PENDING_APPROVAL only, and gives one up while DRAFT only', async () => {
    expectTheFourMethods();
    await api.auth.login(sales);
    const quotation = await draftOn18();
    const { attachment: first } = await api.dealQuotations.uploadAttachment(quotation.id, pdf('first.pdf'));

    await api.dealQuotations.submit(quotation.id);
    const { attachment: second } = await api.dealQuotations.uploadAttachment(quotation.id, pdf('second.pdf'));
    await expect(api.dealQuotations.deleteAttachment(first.id)).rejects.toMatchObject({ status: 409 });

    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(quotation.id, {});
    await api.auth.login(sales);
    await expect(api.dealQuotations.uploadAttachment(quotation.id, pdf('late.pdf'))).rejects.toMatchObject({ status: 409 });
    await expect(api.dealQuotations.deleteAttachment(second.id)).rejects.toMatchObject({ status: 409 });
    expect(ids(await api.dealQuotations.listAttachments(quotation.id)).sort()).toEqual([first.id, second.id].sort());
  });

  it('refuses the eleventh file and a type that is not PDF, JPG or PNG, and keeps neither', async () => {
    expectTheFourMethods();
    await api.auth.login(sales);
    const quotation = await draftOn18();

    await expect(api.dealQuotations.uploadAttachment(quotation.id, new File(['<html>'], 'page.html', { type: 'text/html' })))
      .rejects.toMatchObject({ status: 400 });
    for (let n = 1; n <= 10; n += 1) {
      await api.dealQuotations.uploadAttachment(quotation.id, pdf(`vendor-${n}.pdf`));
    }
    await expect(api.dealQuotations.uploadAttachment(quotation.id, pdf('vendor-11.pdf')))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('10') });
    expect((await api.dealQuotations.listAttachments(quotation.id)).items).toHaveLength(10);
  });

  it('a revision lists the earlier document\'s files as inherited; a reorder starts with none', async () => {
    expectTheFourMethods();
    await api.auth.login(sales);
    const first = await draftOn18();
    const { attachment: onFirst } = await api.dealQuotations.uploadAttachment(first.id, pdf('first.pdf'));
    await api.dealQuotations.submit(first.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(first.id, {});
    await api.auth.login(sales);
    const { quotation: reorder } = await api.dealQuotations.createReorder(first.id);
    const { quotation: revision } = await api.dealQuotations.createRevision(first.id);
    const { attachment: onRevision } = await api.dealQuotations.uploadAttachment(revision.id, pdf('second.pdf'));

    const listed = (await api.dealQuotations.listAttachments(revision.id)).items;
    expect(listed.map((item) => [item.id, item.quotationId, item.quotationNumber, item.inherited])).toEqual([
      [onRevision.id, revision.id, revision.number, false],
      [onFirst.id, first.id, first.number, true],
    ]);
    // An inherited file is the earlier document's: not removable, though the revision is a draft.
    await expect(api.dealQuotations.deleteAttachment(onFirst.id)).rejects.toMatchObject({ status: 409 });
    expect((await api.dealQuotations.listAttachments(reorder.id)).items).toEqual([]);
  });
});
