import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealCustomerCard } from './DealCustomerCard.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      customers: {
        search: vi.fn(),
        create: vi.fn(),
        projects: vi.fn(),
        createProject: vi.fn(),
        contacts: vi.fn(),
        createContact: vi.fn(),
      },
    },
  };
});

const testCustomer = { id: 1, name: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด', taxId: '0105565012345' };
const testProject = { id: 1, customerId: 1, name: 'โครงการ Central Ladprao ชั้น B1' };
const testContact = { id: 1, customerId: 1, firstName: 'วิภา', lastName: 'สมิทธ์' };

function Harness({ initial, showToast = vi.fn() }) {
  const [value, setValue] = React.useState(initial ?? { customer: null, project: null, contact: null, entryChannel: 'UNSPECIFIED' });
  return <DealCustomerCard value={value} onChange={(patch) => setValue((prev) => ({ ...prev, ...patch }))} showToast={showToast} />;
}

describe('DealCustomerCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.projects.mockResolvedValue({ projects: [testProject] });
    api.customers.contacts.mockResolvedValue({ contacts: [testContact] });
  });

  it('searches as the rep types, and selecting a result replaces the input with a value chip', async () => {
    api.customers.search.mockResolvedValue({ customers: [testCustomer] });
    render(<Harness />);

    fireEvent.change(screen.getByLabelText(/^ลูกค้า/), { target: { value: 'ก้าวหน้า' } });
    await waitFor(() => expect(api.customers.search).toHaveBeenCalledWith('ก้าวหน้า'));

    const option = await screen.findByRole('button', { name: new RegExp(testCustomer.name) });
    fireEvent.mouseDown(option);

    expect(screen.getByText(testCustomer.name)).not.toBeNull();
    expect(screen.queryByLabelText(/^ลูกค้า/)).toBeNull(); // the search input is gone, replaced by the chip
    // Loads โครงการ/ผู้ติดต่อ for the newly-picked customer.
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(testCustomer.id));
    await waitFor(() => expect(api.customers.contacts).toHaveBeenCalledWith(testCustomer.id));
  });

  it('clearing the selected customer resets โครงการ/ผู้ติดต่อ too', async () => {
    render(<Harness initial={{ customer: testCustomer, project: testProject, contact: testContact, entryChannel: 'UNSPECIFIED' }} />);

    fireEvent.click(screen.getByRole('button', { name: 'ล้างลูกค้าที่เลือก' }));

    expect(screen.queryByText(testCustomer.name)).toBeNull();
    expect(await screen.findByLabelText(/^ลูกค้า/)).not.toBeNull(); // search input is back
    expect(screen.getByLabelText(/^โครงการ/).disabled).toBe(true);
  });

  it('เพิ่มลูกค้าใหม่ creates a customer and selects it, without ever calling customers.search for the new name', async () => {
    api.customers.search.mockResolvedValue({ customers: [] });
    api.customers.create.mockResolvedValue({ customer: { id: 99, name: 'บริษัท ใหม่ จำกัด', taxId: null } });
    render(<Harness />);

    fireEvent.change(screen.getByLabelText(/^ลูกค้า/), { target: { value: 'xyz' } });
    fireEvent.focus(screen.getByLabelText(/^ลูกค้า/));
    fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));

    const nameInput = screen.getByPlaceholderText('บริษัท … จำกัด');
    fireEvent.change(nameInput, { target: { value: 'บริษัท ใหม่ จำกัด' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกลูกค้าใหม่' }));

    await waitFor(() => expect(api.customers.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'บริษัท ใหม่ จำกัด' })));
    expect(await screen.findByText('บริษัท ใหม่ จำกัด')).not.toBeNull();
  });

  it('F3: เพิ่มลูกค้าใหม่ seeds the name field from what was already typed into the typeahead', async () => {
    api.customers.search.mockResolvedValue({ customers: [] });
    render(<Harness />);

    fireEvent.change(screen.getByLabelText(/^ลูกค้า/), { target: { value: 'บริษัท ใหม่ จำกัด' } });
    await waitFor(() => expect(api.customers.search).toHaveBeenCalledWith('บริษัท ใหม่ จำกัด'));
    fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));

    // Was emptyNewCustomer()'s '' before the fix -- the rep had to retype the name they had
    // already typed into the search box to get here.
    expect(screen.getByPlaceholderText('บริษัท … จำกัด').value).toBe('บริษัท ใหม่ จำกัด');
  });

  it('F3: เพิ่มลูกค้าใหม่ from an empty search box still opens with an empty name field', async () => {
    render(<Harness />);

    fireEvent.focus(screen.getByLabelText(/^ลูกค้า/));
    fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));

    expect(screen.getByPlaceholderText('บริษัท … จำกัด').value).toBe('');
  });

  it('โครงการใหม่ creates a project under the selected customer and selects it', async () => {
    api.customers.createProject.mockResolvedValue({ project: { id: 2, customerId: 1, name: 'โครงการใหม่ทดสอบ' } });
    render(<Harness initial={{ customer: testCustomer, project: null, contact: null, entryChannel: 'UNSPECIFIED' }} />);

    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(testCustomer.id));
    fireEvent.change(await screen.findByLabelText(/^โครงการ/), { target: { value: '__new__' } });

    const nameInput = screen.getByPlaceholderText('ชื่อโครงการ');
    fireEvent.change(nameInput, { target: { value: 'โครงการใหม่ทดสอบ' } });
    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มโครงการ' }));

    await waitFor(() => expect(api.customers.createProject).toHaveBeenCalledWith(testCustomer.id, { name: 'โครงการใหม่ทดสอบ' }));
    await waitFor(() => expect(screen.getByLabelText(/^โครงการ/).value).toBe('2'));
  });

  it('a create-customer failure toasts the backend message and keeps the inline form open', async () => {
    const showToast = vi.fn();
    api.customers.create.mockRejectedValue(new Error('ชื่อลูกค้าซ้ำในระบบ'));
    render(<Harness showToast={showToast} />);

    fireEvent.focus(screen.getByLabelText(/^ลูกค้า/));
    fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));
    fireEvent.change(screen.getByPlaceholderText('บริษัท … จำกัด'), { target: { value: 'บริษัท ซ้ำ จำกัด' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกลูกค้าใหม่' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ชื่อลูกค้าซ้ำในระบบ'));
    // Still open -- the rep doesn't lose what they typed.
    expect(screen.getByPlaceholderText('บริษัท … จำกัด').value).toBe('บริษัท ซ้ำ จำกัด');
  });

  it('ช่องทางรับงาน defaults to ไม่ระบุ and toggles to the picked code on click', () => {
    render(<Harness />);
    const unspecified = screen.getByRole('button', { name: /ไม่ระบุ/ });
    expect(unspecified.getAttribute('aria-pressed')).toBe('true');

    const designerLed = screen.getAllByRole('button').find((b) => b.textContent.includes('ผู้ออกแบบ'));
    fireEvent.click(designerLed);
    expect(designerLed.getAttribute('aria-pressed')).toBe('true');
    expect(unspecified.getAttribute('aria-pressed')).toBe('false');
  });

  it('renders Thai inline error hints passed via the errors prop', () => {
    render(
      <DealCustomerCard
        value={{ customer: null, project: null, contact: null, entryChannel: 'UNSPECIFIED' }}
        onChange={vi.fn()}
        errors={{ customer: 'กรุณาเลือกลูกค้า', project: 'กรุณาเลือกโครงการ' }}
      />,
    );
    expect(screen.getByText('กรุณาเลือกลูกค้า')).not.toBeNull();
    expect(screen.getByText('กรุณาเลือกโครงการ')).not.toBeNull();
  });
});
