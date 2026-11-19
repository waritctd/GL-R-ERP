import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealQuickActions } from './DealQuickActions.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => ({
  ...(await importOriginal()),
  api: { tickets: { addActivity: vi.fn() }, salesVisits: { create: vi.fn() } },
}));

const deal = { ticketId: 701, title: 'โครงการบ้านสวน', customerName: 'ลูกค้า', designerName: '' };

function renderActions(props) {
  const client = new QueryClient();
  return render(
    <QueryClientProvider client={client}>
      <DealQuickActions deal={deal} showToast={vi.fn()} {...props} />
    </QueryClientProvider>,
  );
}

// Slice 6d fix round 1 (review L4, mutation F12): ส่งตัวอย่าง is the same endpoint as โทรแล้ว, so it rides on the same
// gate, canLogCall (TicketService#addActivity's requireDealOwnership). The page-level tests could not catch a button
// shown without it, because the whole row disappears when neither gate holds; here นัดเข้าพบ keeps the row on screen.
describe('DealQuickActions — gates', () => {
  it('wrong way round: without canLogCall there is no ส่งตัวอย่าง and no โทรแล้ว, even while นัดเข้าพบ shows', () => {
    renderActions({ canLogCall: false, canPlanVisit: true });
    expect(screen.getByRole('button', { name: 'นัดเข้าพบ' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'ส่งตัวอย่าง' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'โทรแล้ว' })).toBeNull();
  });
});
