import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealPartiesPanel } from './DealPartiesPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { addParty: vi.fn(), updateParty: vi.fn(), removeParty: vi.fn() },
      customers: { search: vi.fn(), contacts: vi.fn() },
    },
  };
});

const bidder = (overrides) => ({
  id: 1, ticketId: 18, customerId: 5, customerName: 'บริษัท ก จำกัด', contactName: null, role: 'BUYER', status: 'QUOTED',
  headline: false, latestQuotationId: 11, latestQuotationNumber: 'QT-2026-0011-1', latestQuotationStatus: 'APPROVED', latestQuotationAmount: 1000,
  ...overrides,
});

function renderPanel(props) {
  const showToast = vi.fn();
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <MemoryRouter>
        <DealPartiesPanel ticketId={18} showToast={showToast} {...props} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { showToast };
}

describe('DealPartiesPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.search.mockResolvedValue({ customers: [{ id: 10, name: 'บริษัท ตัวอย่าง คอนสตรัคชั่น จำกัด' }] });
    api.customers.contacts.mockResolvedValue({ contacts: [{ id: 11, firstName: 'ประมูล', lastName: 'สาม' }] });
    api.tickets.addParty.mockResolvedValue({ party: bidder({ id: 9 }) });
    api.tickets.updateParty.mockResolvedValue({ party: bidder() });
  });

  it('+ เพิ่มผู้รับเหมา: pick a customer and a contact, then addParty as BUYER', async () => {
    const { showToast } = renderPanel({ parties: [bidder()], canEdit: true });
    fireEvent.click(screen.getByTestId('add-party'));
    const dialog = within(await screen.findByTestId('add-party-modal'));
    fireEvent.change(dialog.getByLabelText(/ผู้รับเหมา/), { target: { value: 'ตัวอย่าง' } });
    fireEvent.click(await dialog.findByRole('option', { name: 'บริษัท ตัวอย่าง คอนสตรัคชั่น จำกัด' }));
    await dialog.findByRole('option', { name: 'ประมูล สาม' });
    fireEvent.change(dialog.getByLabelText('ผู้ติดต่อ'), { target: { value: '11' } });
    fireEvent.click(dialog.getByRole('button', { name: 'เพิ่มผู้รับเหมา' }));
    await waitFor(() => expect(api.tickets.addParty).toHaveBeenCalledWith(18, { customerId: 10, role: 'BUYER', contactId: 11 }));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'เพิ่มผู้รับเหมาแล้ว'));
  });

  it('a 409 stays in the dialog', async () => {
    api.tickets.addParty.mockRejectedValue(Object.assign(new Error('อยู่ในดีลนี้ในบทบาทนี้แล้ว'), { status: 409 }));
    renderPanel({ parties: [bidder()], canEdit: true });
    fireEvent.click(screen.getByTestId('add-party'));
    const dialog = within(await screen.findByTestId('add-party-modal'));
    fireEvent.change(dialog.getByLabelText(/ผู้รับเหมา/), { target: { value: 'ตัวอย่าง' } });
    fireEvent.click(await dialog.findByRole('option', { name: 'บริษัท ตัวอย่าง คอนสตรัคชั่น จำกัด' }));
    fireEvent.click(dialog.getByRole('button', { name: 'เพิ่มผู้รับเหมา' }));
    expect((await dialog.findByRole('alert')).textContent).toContain('อยู่ในดีลนี้ในบทบาทนี้แล้ว');
  });

  it('read-only viewers see the rows but no add, price or ⋯ controls', () => {
    renderPanel({ parties: [bidder()], canEdit: false });
    expect(screen.getAllByText('บริษัท ก จำกัด').length).toBeGreaterThan(0);
    expect(screen.queryByTestId('add-party')).toBeNull();
    expect(screen.queryByText('แก้ไขราคา')).toBeNull();
  });

  it('⋯ sets the headline through updateParty', async () => {
    renderPanel({ parties: [bidder()], canEdit: true });
    const row = screen.getByTestId('party-row-1');
    fireEvent.click(within(row).getByRole('button', { name: /การดำเนินการของ/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '★ ตั้งเป็นยอดหลัก' }));
    await waitFor(() => expect(api.tickets.updateParty).toHaveBeenCalledWith(18, 1, { headline: true }));
  });
});
