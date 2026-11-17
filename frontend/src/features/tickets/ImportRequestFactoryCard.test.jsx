import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ImportRequestFactoryCard } from './ImportRequestFactoryCard.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({ api: { storedImportRequests: {} } }));

const issued = {
  id: 41, ticketId: 701, factoryName: 'Equipe', status: 'ISSUED', version: 1, docNumber: 'IR-2026-0041',
  importStep: 'IN_TRANSIT', importStepAt: '2026-11-06T00:00:00Z', items: [], lines: [],
};
const draft = { ...issued, id: 42, status: 'DRAFT', docNumber: null, importStep: null };

function renderCard(props) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ImportRequestFactoryCard
        ticketId={701} canFullWrite={false} canFooterWrite={false} canSetLeadTime={false} canEmailWrite={false}
        showToast={vi.fn()} {...props}
      />
    </QueryClientProvider>,
  );
}

// Fix round 1 (rulings 7 and 9): the card is shared with import's own ImportDealPage, which is not in the deal-page
// slice. Its wording there stays as it was, and the "ดูอย่างเดียว" chip is the deal page's opt-in, shown only on a
// card that offers this viewer nothing to edit.
describe('ImportRequestFactoryCard — what the deal page changed stays on the deal page', () => {
  it('by default (ImportDealPage) the draft hint also says งาน (owner ruling 2026-11-17)', () => {
    renderCard({ row: draft });
    expect(screen.getByTestId('ir-draft-hint-42').textContent)
      .toBe('ยังเป็นร่าง — ยังไม่มีขั้นนำเข้าให้เลื่อน เมื่อฝ่ายขายเจ้าของงานหรือ CEO ออกเลขแล้ว จะติดตามการนำเข้ารายโรงงาน (S12–S17) แทนการเลื่อนสถานะทั้งงาน');
  });

  it('the deal page asks for งาน', () => {
    renderCard({ row: draft, dealNoun: 'งาน' });
    expect(screen.getByTestId('ir-draft-hint-42').textContent).toContain('เจ้าของงาน');
    expect(screen.getByTestId('ir-draft-hint-42').textContent).not.toContain('ดีล');
  });

  it('wrong-way: no ดูอย่างเดียว chip by default (ImportDealPage), even on a read-only issued card', () => {
    renderCard({ row: issued });
    expect(screen.queryByText('ดูอย่างเดียว')).toBeNull();
  });

  it('the deal page: ดูอย่างเดียว on an issued card that offers this viewer no edit', () => {
    renderCard({ row: issued, showReadOnlyChip: true });
    expect(screen.getByText('ดูอย่างเดียว')).toBeTruthy();
  });

  it.each([
    ['ออกฉบับแก้ไข (canFullWrite)', { canFullWrite: true }],
    ['the lead-time editor (canSetLeadTime)', { canSetLeadTime: true }],
    ['the CEO footer (canFooterWrite)', { canFooterWrite: true }],
    ['the order e-mail (showOrderEmail && canEmailWrite)', { showOrderEmail: true, canEmailWrite: true }],
  ])('wrong-way: no chip on a card that offers %s', (_label, flags) => {
    renderCard({ row: issued, showReadOnlyChip: true, ...flags });
    expect(screen.queryByText('ดูอย่างเดียว')).toBeNull();
  });

  it('wrong-way: no chip on a draft', () => {
    renderCard({ row: draft, showReadOnlyChip: true });
    expect(screen.queryByText('ดูอย่างเดียว')).toBeNull();
  });
});
