import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketCreateModal } from './TicketCreateModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// สร้างงานใหม่ rebuilt to the owner-approved drawing m-create (2026-11-16): five fields, no accordion, no channel
// picker, no items, no client-side drafts. This file REPLACES the accordion-era suite: every test there pinned a
// section, the draft picker/autosave, the entry-channel radios or the item editor, all of which the drawing drops.

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      customers: { search: vi.fn(), projects: vi.fn(), createProject: vi.fn(), create: vi.fn() },
      tickets: { list: vi.fn() },
      dealQuotations: { displayNameOptions: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn(), create: vi.fn() },
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

const dialog = () => screen.getByTestId('ticket-create-modal');
const submitButton = () => screen.getByTestId('ticket-create-submit');

function typeProject(name) {
  fireEvent.change(screen.getByLabelText(/ชื่อโครงการ/), { target: { value: name } });
}

async function pickCustomer() {
  const input = screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…');
  fireEvent.focus(input);
  fireEvent.change(input, { target: { value: 'ลุมพินี' } });
  fireEvent.mouseDown(await screen.findByText(CUSTOMER.name));
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
  it('shows the drawn title, subtitle and five fields, and nothing the drawing dropped', () => {
    renderModal();

    expect(within(dialog()).getByRole('heading', { name: 'สร้างงานใหม่' })).toBeTruthy();
    expect(within(dialog()).getByText('กรอกเท่าที่รู้ตอนนี้ ที่เหลือเติมจากใบเสนอราคาเอง')).toBeTruthy();
    expect(screen.getByLabelText(/ชื่อโครงการ/)).toBeTruthy();
    expect(screen.getByText('ลูกค้า / บริษัท', { exact: false })).toBeTruthy();
    expect(screen.getByText('ผู้ออกแบบ')).toBeTruthy();
    expect(screen.getByText('มูลค่าโดยประมาณ', { exact: false }).textContent).toContain('(ไม่บังคับ)');
    const start = screen.getByRole('radiogroup', { name: 'เริ่มที่' });
    expect(within(start).getByRole('radio', { name: /S1 · อยู่ระหว่างเสนอสินค้า/ }).getAttribute('aria-checked')).toBe('true');
    expect(within(start).getByRole('radio', { name: /S2 · ผู้ออกแบบตกลงสเปค/ }).getAttribute('aria-checked')).toBe('false');
    expect(submitButton().textContent).toContain('สร้างงาน');
    // Dropped by the drawing: the channel picker, the item editor, the draft machinery and the progress bar.
    expect(screen.queryByText(/เสนอแก่/)).toBeNull();
    expect(screen.queryByText(/รายการสินค้า/)).toBeNull();
    expect(screen.queryByText(/บันทึกร่าง/)).toBeNull();
    expect(screen.queryByText(/เสร็จ$/)).toBeNull();
    // The secondary details stay collapsed until asked for.
    expect(screen.queryByLabelText(/เจ้าของงาน/)).toBeNull();
  });

  it('refuses to submit without a project name and a customer, saying which', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    fireEvent.click(submitButton());

    expect(await screen.findByText('กรุณากรอกชื่อโครงการ')).toBeTruthy();
    expect(screen.getByText('กรุณาเลือกบริษัท/ลูกค้า')).toBeTruthy();
    expect(screen.getByLabelText(/ชื่อโครงการ/).getAttribute('aria-invalid')).toBe('true');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('a new project name is created under the chosen customer, and the payload carries only the drawn fields', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    typeProject('  โครงการใหม่  ');
    await pickCustomer();
    fireEvent.click(submitButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(api.customers.createProject).toHaveBeenCalledWith(CUSTOMER.id, { name: 'โครงการใหม่' });
    const payload = onSubmit.mock.calls[0][0];
    expect(payload).toMatchObject({
      title: 'โครงการใหม่',
      customerId: CUSTOMER.id,
      customerName: CUSTOMER.name,
      projectId: 77,
      startStage: 'LEAD_APPROACH',
      estimatedValue: null,
      designerName: null,
      priority: 'NORMAL',
      items: [],
    });
    // No channel is asked, so none is sent: the server stores UNSPECIFIED.
    expect('entryChannel' in payload).toBe(false);
    expect('ownerEmployeeId' in payload).toBe(false);
    // The follow-up date the readiness gate needs is still defaulted (+14 days), as before.
    expect(payload.nextFollowUpAt).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it("picking one of the customer's existing projects uses it and creates nothing", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    await pickCustomer();
    fireEvent.click(await screen.findByRole('button', { name: EXISTING_PROJECT.name }));
    fireEvent.click(submitButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(api.customers.createProject).not.toHaveBeenCalled();
    expect(onSubmit.mock.calls[0][0].projectId).toBe(EXISTING_PROJECT.id);
    expect(screen.getByLabelText(/ชื่อโครงการ/).value).toBe(EXISTING_PROJECT.name);
  });

  it('a typed name that matches an existing project of the customer reuses it (no duplicate project)', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    typeProject(' โครงการบ้านสวนลุมพินี ');
    await pickCustomer();
    await screen.findByRole('button', { name: EXISTING_PROJECT.name });
    fireEvent.click(submitButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(api.customers.createProject).not.toHaveBeenCalled();
    expect(onSubmit.mock.calls[0][0].projectId).toBe(EXISTING_PROJECT.id);
  });

  it('เริ่มที่ S2 and a value with thousands separators reach the payload', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    typeProject('โครงการใหม่');
    await pickCustomer();
    fireEvent.change(screen.getByLabelText(/มูลค่าโดยประมาณ/), { target: { value: '1,200,000' } });
    fireEvent.click(screen.getByRole('radio', { name: /S2 · ผู้ออกแบบตกลงสเปค/ }));
    fireEvent.click(submitButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ startStage: 'SPEC_APPROVED', estimatedValue: 1200000 });
  });

  it('a value that is not a non-negative number is refused in place', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    typeProject('โครงการใหม่');
    await pickCustomer();
    fireEvent.change(screen.getByLabelText(/มูลค่าโดยประมาณ/), { target: { value: '-5' } });
    fireEvent.click(submitButton());

    expect(await screen.findByText('มูลค่าต้องเป็นตัวเลขไม่ติดลบ')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('a designer picked from the directory is sent by name', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    typeProject('โครงการใหม่');
    await pickCustomer();
    const designer = screen.getByRole('combobox', { name: /ผู้ออกแบบ/ });
    fireEvent.focus(designer);
    fireEvent.change(designer, { target: { value: 'เทพ' } });
    fireEvent.mouseDown(await screen.findByRole('option', { name: /D042/ }));
    fireEvent.click(submitButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(api.designers.getByCode).toHaveBeenCalledWith('D042');
    expect(onSubmit.mock.calls[0][0].designerName).toBe(DESIGNER.name);
  });

  it('keeps the customer "add new" sub-form the picker needs', async () => {
    renderModal();
    const input = screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…');
    fireEvent.focus(input);
    fireEvent.mouseDown(await screen.findByText('เพิ่มลูกค้าใหม่'));
    expect(screen.getByPlaceholderText('บริษัท … จำกัด')).toBeTruthy();
  });

  it('the secondary details keep เจ้าของงาน: keying a deal for a colleague still sends ownerEmployeeId', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    typeProject('โครงการใหม่');
    await pickCustomer();
    fireEvent.click(screen.getByRole('button', { name: /รายละเอียดเพิ่มเติม/ }));
    const owner = await screen.findByLabelText(/เจ้าของงาน/);
    await within(owner).findByRole('option', { name: 'คุณวิมล ขายเก่ง' });
    fireEvent.change(owner, { target: { value: '9' } });
    fireEvent.click(submitButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].ownerEmployeeId).toBe(9);
  });

  it('a failed create shows the server message and lets the rep try again', async () => {
    const onSubmit = vi.fn().mockRejectedValueOnce(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    renderModal({ onSubmit });

    typeProject('โครงการใหม่');
    await pickCustomer();
    fireEvent.click(submitButton());

    expect(await screen.findByText('ไม่มีสิทธิ์เข้าถึงรายการนี้')).toBeTruthy();
    expect(submitButton().disabled).toBe(false);
  });

  it('writes nothing to localStorage (the five-field form has no drafts)', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    renderModal();

    typeProject('โครงการใหม่');
    await new Promise((resolve) => { setTimeout(resolve, 1100); });

    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });
});
