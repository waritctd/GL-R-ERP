import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketCreateModal } from './TicketCreateModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      customers: { search: vi.fn(), projects: vi.fn(), createProject: vi.fn(), create: vi.fn() },
      tickets: { list: vi.fn() },
      dealQuotations: { displayNameOptions: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn(), create: vi.fn() },
      // The item section's catalog autocomplete (ticketItemFields.searchCatalog).
      catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) },
    },
  };
});

const currentUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const OWNER_OPTIONS = [{ id: 6, name: 'คุณสมหมาย ขายดี' }, { id: 9, name: 'คุณวิมล ขายเก่ง' }];
const CUSTOMER = { id: 1, name: 'บริษัท ลุมพินี เรสซิเดนซ์ จำกัด', taxId: null };
const EXISTING_PROJECT = { id: 10, name: 'โครงการบ้านสวนลุมพินี' };
const DESIGNER = { code: 'D042', name: 'เทพสตูดิโอ ดีไซน์', active: true };

function renderModal(props = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <TicketCreateModal user={currentUser} onClose={() => {}} onSubmit={vi.fn()} {...props} />
    </QueryClientProvider>,
  );
}

const submitButton = () => screen.getByTestId('ticket-create-submit');

function typeProject(name) {
  fireEvent.change(screen.getByLabelText(/ชื่อโครงการ/), { target: { value: name } });
}

// Picks the customer AND the required channel (ruling 1), so the happy-path tests below have a complete form.
async function pickCustomer() {
  const input = screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…');
  fireEvent.focus(input);
  fireEvent.change(input, { target: { value: 'ลุมพินี' } });
  fireEvent.mouseDown(await screen.findByText(CUSTOMER.name));
  fireEvent.click(screen.getByRole('radio', { name: 'ผู้ออกแบบนำ' }));
}

beforeEach(() => {
  vi.clearAllMocks();
  api.customers.search.mockResolvedValue({ customers: [CUSTOMER] });
  api.customers.projects.mockResolvedValue({ projects: [EXISTING_PROJECT] });
  api.customers.createProject.mockResolvedValue({ project: { id: 77, name: 'โครงการใหม่' } });
  api.tickets.list.mockResolvedValue({ tickets: [] });
  api.dealQuotations.displayNameOptions.mockResolvedValue({ items: OWNER_OPTIONS });
  api.designers.search.mockResolvedValue({ items: [DESIGNER] });
  api.designers.getByCode.mockResolvedValue(DESIGNER);
});

describe('TicketCreateModal — the drawn short form (m-create)', () => {
  it('refuses to submit without a project name and a customer, saying which', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    fireEvent.click(submitButton());

    expect(await screen.findByText('กรุณากรอกชื่อโครงการ')).toBeTruthy();
    expect(screen.getByText('กรุณาเลือกบริษัท/ลูกค้า')).toBeTruthy();
    expect(screen.getByText('กรุณาเลือกว่างานนี้เสนอแก่ใคร')).toBeTruthy();
    expect(screen.getByLabelText(/ชื่อโครงการ/).getAttribute('aria-invalid')).toBe('true');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('สั่งซื้อแล้ว (a deal that ran before the portal) sends startStage PROCUREMENT', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    typeProject('โครงการเก่า');
    await pickCustomer();
    fireEvent.click(screen.getByRole('radio', { name: /สั่งซื้อแล้ว/ }));
    // The deposit already received is required (0 allowed): nothing is sent without it.
    fireEvent.click(submitButton());
    expect(await screen.findByText(/ใส่มัดจำที่รับแล้ว/)).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
    // Above 0 the date it was received is required too.
    fireEvent.change(screen.getByLabelText('มัดจำที่รับแล้วก่อนใช้ระบบ (บาท)'), { target: { value: '500' } });
    fireEvent.click(submitButton());
    expect(await screen.findByText('ใส่วันที่ได้รับมัดจำ')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('มัดจำที่รับแล้วก่อนใช้ระบบ (บาท)'), { target: { value: '0' } });
    fireEvent.click(submitButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ startStage: 'PROCUREMENT', preDeposit: 0 });
  });

  it('refuses a deal with no channel (owner rule 2026-09-30, kept in fix round 1)', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    typeProject('โครงการใหม่');
    const input = screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…');
    fireEvent.focus(input);
    fireEvent.mouseDown(await screen.findByText(CUSTOMER.name));
    fireEvent.click(submitButton());

    expect(await screen.findByText('กรุณาเลือกว่างานนี้เสนอแก่ใคร')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});

// Fix round 1, ruling 2: no capability lost at creation. Only a `sales` creator may edit items afterwards
// (TicketService.editItems: SALES_ROLES owner/keyer), so only for the OTHER creators does the form keep an optional,
// collapsed รายการสินค้า section — written wrong-way-round per role.
describe('TicketCreateModal — items for creators who cannot add them later', () => {
  it('a sales creator gets no item section and sends items: [] (they add items on the deal page)', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, user: { id: 6, name: 'ขาย', role: 'sales', canCreateQuotation: true } });

    expect(screen.queryByRole('button', { name: /รายการสินค้า/ })).toBeNull();
    typeProject('โครงการใหม่');
    await pickCustomer();
    fireEvent.click(submitButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items).toEqual([]);
  });
});
