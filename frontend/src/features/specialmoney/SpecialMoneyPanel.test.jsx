import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, within, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SpecialMoneyPanel } from './SpecialMoneyPanel.jsx';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    specialMoney: {
      employees: vi.fn(),
      types: vi.fn(),
      list: vi.fn(),
      usage: vi.fn(),
      create: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
      attachments: vi.fn(),
      addAttachment: vi.fn(),
      attachmentDownloadUrl: vi.fn((id) => `/api/special-money/attachments/${id}`),
    },
  },
}));

const user = {
  employeeId: 1,
  name: 'พนักงาน ทดสอบ',
  role: 'employee',
  manager: false,
};

const currentEmployee = {
  id: 1,
  nameTh: 'พนักงาน ทดสอบ',
};

// Matches SpecialMoneyController's GET /types shape (thin subset covering every category the type picker's
// groups render).
const TYPES = [
  { requestType: 'TRAVEL_PER_DIEM', thaiLabel: 'เบี้ยเลี้ยงเดินทาง', payrollBucket: 'PER_DIEM', evidenceRequired: false },
  { requestType: 'MEDICAL', thaiLabel: 'ค่ารักษาพยาบาล', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'AID_WEDDING', thaiLabel: 'เงินช่วยเหลืองานแต่งงาน', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'AID_FUNERAL', thaiLabel: 'เงินช่วยเหลืองานศพ', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'UNIFORM_ANNUAL', thaiLabel: 'ชุดฟอร์มประจำปี', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'UNIFORM_PREPROBATION_KIT', thaiLabel: 'ชุดก่อนผ่านทดลองงาน', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'OTHER', thaiLabel: 'อื่นๆ', payrollBucket: 'AID', evidenceRequired: true },
];

const TYPE_LABEL_BY_VALUE = Object.fromEntries(TYPES.map((type) => [type.requestType, type.thaiLabel]));

// `showToast` defaults to a throwaway spy, so every existing call site is unchanged; pass one in to assert on
// what the panel announced (e.g. that a filed request never raises an error toast).
function renderPanel(as = user, showToast = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const view = render(
    <QueryClientProvider client={queryClient}>
      <SpecialMoneyPanel user={as} currentEmployee={currentEmployee} showToast={showToast} />
    </QueryClientProvider>,
  );
  // Exposed for tests that need to inspect the cache directly (e.g. counting distinct
  // ['specialMoney','list',...] entries) rather than inferring cache-key separation from fetch call counts,
  // which react-query's own staleness/refetch-on-mount behaviour can make unreliable.
  return { ...view, queryClient, showToast };
}

const ceoUser = { employeeId: 9, name: 'ซีอีโอ', role: 'ceo', manager: false };
const managerUser = { employeeId: 8, name: 'ผู้จัดการ', role: 'employee', manager: true, divisionId: 5 };

function submittedRequest(overrides = {}) {
  return {
    id: 4001,
    employeeId: 1,
    employeeName: 'พนักงาน ทดสอบ',
    requestType: 'AID_WEDDING',
    eventDate: '2026-07-01',
    requestedAmount: 5000,
    approvedAmount: null,
    status: 'SUBMITTED',
    payrollBucket: 'AID',
    detail: {},
    requestedById: 1,
    managerEmployeeId: 8,
    attachmentCount: 0,
    ...overrides,
  };
}

// TypePicker is a two-step group -> radio disclosure. This helper opens every group (so a caller
// never has to know which group a type lives in) and clicks the matching radio.
async function selectType(value) {
  const picker = await screen.findByLabelText(/ประเภทคำขอ/);
  // The label/wrapper renders immediately, but TypePicker's groups only populate once GET /types
  // resolves: wait for at least one group toggle before opening/clicking.
  await waitFor(() => expect(within(picker).queryAllByRole('button').length).toBeGreaterThan(0));
  const toggles = within(picker).getAllByRole('button');
  toggles.forEach((toggle) => {
    if (toggle.getAttribute('aria-expanded') === 'false') fireEvent.click(toggle);
  });
  const label = TYPE_LABEL_BY_VALUE[value];
  const radio = await within(picker).findByRole('radio', { name: label });
  fireEvent.click(radio);
  await waitFor(() => expect(radio.checked).toBe(true));
  return radio;
}

// Date fields commit typed text on blur, as a user leaving the field does.
function typeDate(el, value) {
  fireEvent.change(el, { target: { value } });
  fireEvent.blur(el);
}

describe('SpecialMoneyPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.specialMoney.employees.mockResolvedValue({
      employees: [{
        employeeId: 1,
        employeeName: 'พนักงาน ทดสอบ',
        employeeCode: 'GLR-001',
        self: true,
        directReport: false,
      }],
    });
    api.specialMoney.types.mockResolvedValue({ types: TYPES });
    api.specialMoney.list.mockResolvedValue({ requests: [] });
    api.specialMoney.usage.mockResolvedValue({
      usage: {
        employeeId: 1,
        year: new Date().getFullYear(),
        approvedAmountThisYearByType: {},
        approvedCountLifetimeByType: {},
        activeCountThisYearByType: {},
      },
    });
    api.specialMoney.create.mockResolvedValue({ request: { id: 3001 } });
    api.specialMoney.attachments.mockResolvedValue({ attachments: [] });
    api.specialMoney.addAttachment.mockResolvedValue({ attachment: { id: 9001 } });
  });

  it('computes per-diem as days x role rate', async () => {
    renderPanel();

    await selectType('TRAVEL_PER_DIEM');
    const startInput = await screen.findByLabelText(/วันที่เริ่มเดินทาง/);
    const endInput = screen.getByLabelText(/วันที่สิ้นสุด/);
    typeDate(startInput, '2026-07-10');
    typeDate(endInput, '2026-07-12');
    fireEvent.change(screen.getByLabelText(/^บทบาท/), { target: { value: 'driver' } });

    await waitFor(() => {
      expect(screen.getByTestId('smr-computed-amount').textContent).toBe('฿1,200');
    });
    expect(screen.getByText(/3 วัน × ฿400/)).not.toBeNull();
  });

  it('zeroes the amount and warns when the destination province is excluded', async () => {
    renderPanel();

    await selectType('TRAVEL_PER_DIEM');
    fireEvent.change(screen.getByLabelText(/^บทบาท/), { target: { value: 'driver' } });
    fireEvent.change(await screen.findByLabelText(/จังหวัดปลายทาง/), { target: { value: 'นนทบุรี' } });

    await waitFor(() => {
      expect(screen.getByTestId('smr-computed-amount').textContent).toBe('฿0');
    });
    expect(await screen.findByText(/จังหวัดที่ถือเป็นการเดินทางในพื้นที่/)).not.toBeNull();
    const submitButton = screen.getByRole('button', { name: /ส่งคำขอ/ });
    expect(submitButton.disabled).toBe(true);
  });

  it('submits the expected payload shape for a fixed-aid type', async () => {
    renderPanel();

    await selectType('AID_WEDDING');
    typeDate(await screen.findByLabelText(/วันที่เกิดเหตุการณ์/), '2026-07-01');
    fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.specialMoney.create).toHaveBeenCalledTimes(1));
    expect(api.specialMoney.create).toHaveBeenCalledWith({
      requestType: 'AID_WEDDING',
      employeeId: 1,
      eventDate: '2026-07-01',
      eventEndDate: null,
      receiptDate: null,
      requestedAmount: 5000,
      reason: 'ทดสอบระบบ',
      detail: {},
    });
  });

  // The submit-for picker has no live path: SpecialMoneyEmployeeOption.directReport is hardcoded
  // false in Java and resolveTargetEmployee refuses any target but self for every role, so even a
  // stale GET /employees payload with a second roster row must never surface a picker (only the
  // requester's own name, disabled).
  describe('submit-for picker: self-only, no on-behalf select', () => {
    function rosterWithStaleSecondEntry() {
      return {
        employees: [
          { employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001', self: true, directReport: false },
          // Hypothetical stale payload shaped like the old on-behalf list: the frontend must not
          // react to it.
          { employeeId: 8, employeeName: 'ผู้จัดการ', employeeCode: 'GLR-008', self: false, directReport: true },
        ],
      };
    }

    it('renders only the disabled display input, never a <select>/combobox, against a roster with a second entry', async () => {
      api.specialMoney.employees.mockResolvedValue(rosterWithStaleSecondEntry());
      const { queryClient } = renderPanel();

      // The display input shows user.name before GET /employees resolves, so waiting on it alone would assert
      // against an empty roster and pass even with the old picker back in place. Wait until the two-entry roster is
      // in the cache, then let React commit the re-render.
      await waitFor(() => expect(queryClient.getQueryData(queryKeys.specialMoneyEmployees())).toHaveLength(2));
      await act(async () => {});
      await screen.findByDisplayValue('พนักงาน ทดสอบ');
      expect(screen.queryByRole('combobox', { name: /พนักงาน/ })).toBeNull();
      expect(document.getElementById('smr-employee')).toBeNull();
      expect(document.getElementById('smr-employee-display')).not.toBeNull();
      expect(document.getElementById('smr-employee-display').disabled).toBe(true);
    });
  });

  // Mirrors SpecialMoneyService.cancel() / requireCanAttach(): the owning employee only, only while
  // SUBMITTED (no "whoever filed on their behalf" disjunct). These pin the frontend button gate to
  // match, wrong-way-round first.
  describe('cancel/attach gate: owner-only, not requester-only', () => {
    it('offers a legacy on-behalf filer (requestedById matches the viewer) no cancel or upload control on someone else\'s row', async () => {
      const hrUser = { employeeId: 7, name: 'ฝ่ายบุคคล', role: 'hr', manager: false };
      api.specialMoney.list.mockResolvedValue({
        requests: [submittedRequest({ employeeId: 1, requestedById: 7 })],
      });
      renderPanel(hrUser);

      // The row must actually render before the absence of its buttons means anything -- otherwise this assertion
      // would also pass if the row (or the whole panel) failed to render at all.
      await screen.findByText(/พนักงาน ทดสอบ ·/);

      expect(screen.queryByRole('button', { name: 'ยกเลิกคำขอ' })).toBeNull();
      expect(screen.queryByText('เลือกไฟล์')).toBeNull();
    });

    it('offers the owning employee both cancel and upload on their own SUBMITTED row, even though someone else is recorded as requestedById', async () => {
      api.specialMoney.list.mockResolvedValue({
        requests: [submittedRequest({ employeeId: 1, requestedById: 7 })],
      });
      renderPanel(user); // user.employeeId === 1, the row's owning employee

      await screen.findByText(/พนักงาน ทดสอบ ·/);
      await waitFor(() => expect(api.specialMoney.attachments).toHaveBeenCalledWith(4001));

      expect(await screen.findByRole('button', { name: 'ยกเลิกคำขอ' })).not.toBeNull();
      expect(await screen.findByText('เลือกไฟล์')).not.toBeNull();
    });
  });

  // Welfare is CEO-only in one stage. These pin the button gating, which is the half of that rule a user
  // actually meets -- the server-side half is SpecialMoneyScopeIntegrationTest's job.
  describe('approval gating', () => {
    beforeEach(() => {
      api.specialMoney.list.mockResolvedValue({ requests: [submittedRequest()] });
    });

    it('offers the CEO an approve button in the review queue on a SUBMITTED request', async () => {
      renderPanel(ceoUser);

      expect(await screen.findByRole('button', { name: 'CEO อนุมัติ' })).toBeTruthy();
    });

    it('offers a ฝ่าย manager no approve button, even for their own division', async () => {
      // The tempting wrong answer: overtime lets a ฝ่าย manager approve, but welfare has no manager
      // stage, so the button must not appear.
      renderPanel(managerUser);

      await screen.findByText(/พนักงาน ทดสอบ ·/);
      expect(screen.queryByRole('button', { name: 'CEO อนุมัติ' })).toBeNull();
      expect(screen.queryByRole('button', { name: /ผู้จัดการอนุมัติ/ })).toBeNull();
    });

    // The redesign's core defect fix: the CEO must see evidence, and be warned before they can approve an
    // evidence-required type with nothing attached.
    it('warns the CEO and disables approve when an evidence-required type has no attachment', async () => {
      api.specialMoney.list.mockResolvedValue({
        requests: [submittedRequest({ requestType: 'AID_WEDDING', attachmentCount: 0 })],
      });
      renderPanel(ceoUser);

      const approveButton = await screen.findByRole('button', { name: 'CEO อนุมัติ' });
      // The same warning also appears on the request's row in the "My requests" history table, so
      // this scopes to the review queue card that holds the approve button.
      const reviewCard = approveButton.closest('.py-3\\.5');
      expect(within(reviewCard).getByText(/ยังไม่ได้แนบเอกสาร — อนุมัติไม่ได้/)).not.toBeNull();
      expect(approveButton.disabled).toBe(true);
    });
  });

  // The dead end SpecialMoneyApproveDeadEndIntegrationTest.java pins server-side: an empty review
  // (approve(id, {})) 400s whenever the amount exceeds the policy cap, leaving the row stuck
  // SUBMITTED. This dialog exposes both escape routes (a lower approvedAmount, or a
  // capOverrideReason) to the CEO.
  describe('CEO approve dialog', () => {
    beforeEach(() => {
      // attachmentCount: 1 -- AID_WEDDING is evidenceRequired (see TYPES fixture above), and the approve button is
      // disabled without an attachment (unrelated "approval gating" coverage above); this dialog's own tests need it
      // enabled.
      api.specialMoney.list.mockResolvedValue({
        requests: [submittedRequest({ requestedAmount: 5000, attachmentCount: 1 })],
      });
      api.specialMoney.approve.mockResolvedValue({ request: { id: 4001, status: 'APPROVED' } });
    });

    async function openApproveDialog() {
      renderPanel(ceoUser);
      fireEvent.click(await screen.findByRole('button', { name: 'CEO อนุมัติ' }));
      const dialog = await screen.findByRole('dialog', { name: 'ยืนยันการอนุมัติ' });
      return dialog;
    }

    it('sends a lowered amount when the CEO edits the amount field down', async () => {
      const dialog = await openApproveDialog();

      fireEvent.change(within(dialog).getByLabelText(/จำนวนเงินที่อนุมัติ/), { target: { value: '1960' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'อนุมัติ' }));

      await waitFor(() => expect(api.specialMoney.approve).toHaveBeenCalledTimes(1));
      expect(api.specialMoney.approve).toHaveBeenCalledWith(4001, { approvedAmount: 1960, capOverrideReason: null });
    });

    // Amount 0/empty/negative must never reach the API -- Confirm stays disabled instead.
    it.each([['0', '0'], ['', 'empty'], ['-5', 'negative']])(
      'disables Confirm and never calls approve for a %s (%s) amount',
      async (value) => {
        const dialog = await openApproveDialog();

        fireEvent.change(within(dialog).getByLabelText(/จำนวนเงินที่อนุมัติ/), { target: { value } });
        const confirmButton = within(dialog).getByRole('button', { name: 'อนุมัติ' });
        expect(confirmButton.disabled).toBe(true);

        fireEvent.click(confirmButton);
        expect(api.specialMoney.approve).not.toHaveBeenCalled();
      },
    );
  });

  describe('a request that is filed but whose evidence upload fails', () => {
    it('clears the form, so pressing ส่งคำขอ again cannot re-file the accepted payload', async () => {
      api.specialMoney.addAttachment.mockRejectedValueOnce(new Error('เกิดข้อผิดพลาดภายในระบบ'));
      renderPanel();

      await selectType('AID_WEDDING');
      fireEvent.change(await screen.findByLabelText(/วันที่เกิดเหตุการณ์/), { target: { value: '2026-07-01' } });
      fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ทดสอบระบบ' } });
      fireEvent.change(document.getElementById('smr-evidence'), {
        target: { files: [new File(['x'], 'marriage-cert.pdf', { type: 'application/pdf' })] },
      });
      fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));
      await screen.findByText(/บันทึกคำขอเลขที่ 3001 เรียบร้อยแล้ว/);

      // Both halves matter: the notice says "ไม่ต้องส่งคำขอซ้ำ", and the form no longer holds the data that would
      // make sending it again a single click.
      expect(screen.getByText(/ไม่ต้องส่งคำขอซ้ำ/)).not.toBeNull();
      expect(screen.getByLabelText(/เหตุผล/).value).toBe('');
      expect(api.specialMoney.create).toHaveBeenCalledTimes(1);
    });
  });
});
