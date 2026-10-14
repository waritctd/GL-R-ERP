import { describe, expect, it } from 'vitest';
import { api, retireLiveDirectQuotationsForTests, retireLivePricingRequestsForTests } from './mockApi.js';

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

// Slice 2: a direct create needs a recipient (S2-B1) and refuses a deal that already holds a live
// direct quotation (S2-B3, N6) or a live pricing request. Ticket 18's seed holds both, so this
// retires them first -- the state a rep reaches by cancelling -- exactly as mockApi.dealQuotations.test.js does.
const draftOn18 = async () => {
  retireLiveDirectQuotationsForTests(18);
  retireLivePricingRequestsForTests(18);
  return (await api.dealQuotations.create(18, { recipientType: 'DESIGNER', items: [ONE_ITEM] })).quotation;
};
const pdf = (name = 'vendor-quote.pdf') => new File(['%PDF-1.4 vendor cost'], name, { type: 'application/pdf' });
const ids = (answer) => answer.items.map((item) => item.id);

describe('mock dealQuotations, เอกสารแนบ', () => {
  it('upload keeps the file, list returns it, and delete takes it away again', async () => {
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
    expect(api.dealQuotations.attachmentUrl(attachment.id)).toBe(`#mock-deal-quotation-attachment-${attachment.id}`);

    expect(await api.dealQuotations.deleteAttachment(attachment.id)).toEqual({ ok: true });
    expect(await api.dealQuotations.listAttachments(quotation.id)).toEqual({ items: [] });
  });

  it('refuses account and import the list and the upload, and the CEO the upload', async () => {
    await api.auth.login(sales);
    const quotation = await draftOn18();
    const { attachment } = await api.dealQuotations.uploadAttachment(quotation.id, pdf());

    for (const role of ['account', 'import']) {
      await api.auth.login({ role });
      // Control: import opens the quotation itself in the mock. Account no longer opens a DRAFT at
      // all (owner ruling 2026-09-30, ACCOUNT_VISIBLE_DEAL_QUOTATION_STATUSES: it sees a quotation
      // only once it went to the customer), so for account the row gate refuses before the files do.
      if (role === 'import') {
        expect((await api.dealQuotations.get(quotation.id)).quotation.id, role).toBe(quotation.id);
      } else {
        await expect(api.dealQuotations.get(quotation.id), role).rejects.toMatchObject({ status: 403 });
      }
      await expect(api.dealQuotations.listAttachments(quotation.id), role).rejects.toMatchObject({ status: 403 });
      await expect(api.dealQuotations.uploadAttachment(quotation.id, pdf()), role).rejects.toMatchObject({ status: 403 });
      await expect(api.dealQuotations.deleteAttachment(attachment.id), role).rejects.toMatchObject({ status: 403 });
    }

    await api.auth.login({ role: 'ceo' });
    expect(ids(await api.dealQuotations.listAttachments(quotation.id))).toEqual([attachment.id]);
    await expect(api.dealQuotations.uploadAttachment(quotation.id, pdf())).rejects.toMatchObject({ status: 403 });
    await expect(api.dealQuotations.deleteAttachment(attachment.id)).rejects.toMatchObject({ status: 403 });
  });

  // The refusal that belongs to the FILE role, with the status rule out of the way: an APPROVED
  // quotation on a deal inside account's scope (seed deal 19), which account really does open.
  // The DRAFT case above is refused by the status rule before the file rule is ever reached.
  it('refuses account and import the files of an APPROVED quotation they can themselves open', async () => {
    await api.auth.login(sales);
    retireLiveDirectQuotationsForTests(19);
    retireLivePricingRequestsForTests(19);
    const { quotation: created } = await api.dealQuotations.create(19, { recipientType: 'DESIGNER', items: [ONE_ITEM] });
    const { attachment } = await api.dealQuotations.uploadAttachment(created.id, pdf());
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(created.id, {});

    for (const role of ['account', 'import']) {
      await api.auth.login({ role });
      // Control: this role opens the approved quotation itself, so only the file rule can refuse below.
      expect((await api.dealQuotations.get(created.id)).quotation.docStatus, role).toBe('APPROVED');
      await expect(api.dealQuotations.listAttachments(created.id), role).rejects.toMatchObject({ status: 403 });
      await expect(api.dealQuotations.uploadAttachment(created.id, pdf()), role).rejects.toMatchObject({ status: 403 });
      await expect(api.dealQuotations.deleteAttachment(attachment.id), role).rejects.toMatchObject({ status: 403 });
    }
  });

  it('takes files while DRAFT and PENDING_APPROVAL only, and gives one up while DRAFT only', async () => {
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
