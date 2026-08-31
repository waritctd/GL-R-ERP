import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SpecialMoneyPanel } from './SpecialMoneyPanel.jsx';
import { TYPE_GROUPS } from './specialMoneyRules.js';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

// Bangkok-safe year for test expectations -- mirrors the component's own currentBangkokYear()
// (Intl.DateTimeFormat with timeZone: 'Asia/Bangkok'), deliberately NOT `new Date().getFullYear()`
// (local/UTC time). frontend-ci.yml sets no TZ, so CI runs UTC: at e.g.
// 2026-12-31T18:00:00+00:00 that's still 2026 in UTC but already 2027 in Bangkok, so a test built
// on `new Date().getFullYear()` would go red against CORRECT Bangkok-aware component code once a
// year -- the exact `bangkok-timezone-test-flake` trap, recreated in JS if this used local time.
function currentBangkokYearForTest() {
  return Number(new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Bangkok', year: 'numeric' }).format(new Date()));
}

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

// Matches SpecialMoneyController's GET /types shape (thin subset covering
// every category the type picker's groups render).
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

// `showToast` defaults to a throwaway spy, so every existing call site is unchanged; pass one in
// to assert on what the panel announced (e.g. that a filed request never raises an error toast).
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
  // ['specialMoney','list',...] entries) rather than inferring cache-key separation from fetch
  // call counts, which react-query's own staleness/refetch-on-mount behaviour can make unreliable.
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

function rejectedRequest(overrides = {}) {
  return {
    id: 4002,
    employeeId: 1,
    employeeName: 'พนักงาน ทดสอบ',
    requestType: 'MEDICAL',
    eventDate: '2026-06-01',
    requestedAmount: 1000,
    approvedAmount: null,
    status: 'REJECTED',
    payrollBucket: 'NON_TAXABLE',
    detail: {},
    requestedById: 1,
    reviewerNote: 'ใบเสร็จไม่ชัดเจน กรุณาแนบใหม่',
    attachmentCount: 1,
    ...overrides,
  };
}

// TypePicker (plan §"Type grouping") replaced the flat <select> with a two-step
// group -> radio disclosure. This helper opens every group (so a caller never has to know which
// group a given type lives in) and clicks the matching radio, which is what the original
// selectType()'s `fireEvent.change` on a <select> did functionally -- pick a type and let
// react-hook-form register the change.
async function selectType(value) {
  const picker = await screen.findByLabelText(/ประเภทคำขอ/);
  // The label/wrapper renders immediately, but TypePicker's groups only populate once GET /types
  // resolves — wait for at least one group toggle to exist before trying to open/click anything,
  // same reasoning as the original select-based helper waiting for its <option>s.
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

  it('swaps the visible fields when the request type changes', async () => {
    renderPanel();

    await selectType('TRAVEL_PER_DIEM');
    expect(await screen.findByLabelText(/จังหวัดปลายทาง/)).not.toBeNull();
    expect(screen.getByLabelText(/^บทบาท/)).not.toBeNull();
    expect(screen.queryByLabelText(/วันที่ใบเสร็จ/)).toBeNull();

    await selectType('MEDICAL');
    await waitFor(() => expect(screen.queryByLabelText(/จังหวัดปลายทาง/)).toBeNull());
    expect(screen.getByLabelText(/วันที่ใบเสร็จ/)).not.toBeNull();
  });

  it('computes per-diem as days x role rate', async () => {
    renderPanel();

    await selectType('TRAVEL_PER_DIEM');
    const startInput = await screen.findByLabelText(/วันที่เริ่มเดินทาง/);
    const endInput = screen.getByLabelText(/วันที่สิ้นสุด/);
    fireEvent.change(startInput, { target: { value: '2026-07-10' } });
    fireEvent.change(endInput, { target: { value: '2026-07-12' } });
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

  it('flips the tax chip with the selected type', async () => {
    renderPanel();

    await selectType('MEDICAL');
    expect(await screen.findByText('ไม่คิดภาษี')).not.toBeNull();

    await selectType('AID_WEDDING');
    await waitFor(() => expect(screen.getByText('คิดภาษี')).not.toBeNull());
  });

  it('submits the expected payload shape for a fixed-aid type', async () => {
    renderPanel();

    await selectType('AID_WEDDING');
    fireEvent.change(await screen.findByLabelText(/วันที่เกิดเหตุการณ์/), { target: { value: '2026-07-01' } });
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

  // Backend contract trap (`detail` is Map<String,String> on the wire): every value sent, numeric
  // or boolean, must already be a string by the time it reaches create()'s payload.
  describe('detail payload string-typing per type', () => {
    it('sends TRAVEL_PER_DIEM detail values as strings', async () => {
      renderPanel();

      await selectType('TRAVEL_PER_DIEM');
      fireEvent.change(await screen.findByLabelText(/วันที่เริ่มเดินทาง/), { target: { value: '2026-07-10' } });
      fireEvent.change(screen.getByLabelText(/วันที่สิ้นสุด/), { target: { value: '2026-07-10' } });
      fireEvent.change(screen.getByLabelText(/^บทบาท/), { target: { value: 'driver' } });
      fireEvent.change(await screen.findByLabelText(/จังหวัดปลายทาง/), { target: { value: 'ชลบุรี' } });
      fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ไปส่งของ' } });
      fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

      await waitFor(() => expect(api.specialMoney.create).toHaveBeenCalledTimes(1));
      const payload = api.specialMoney.create.mock.calls[0][0];
      expect(payload.detail).toEqual({ destination: 'DOMESTIC', province: 'ชลบุรี', role: 'driver' });
      Object.values(payload.detail).forEach((value) => expect(typeof value).toBe('string'));
    });

    it('sends UNIFORM_ANNUAL shirt/trouser counts as strings, not numbers', async () => {
      renderPanel();

      await selectType('UNIFORM_ANNUAL');
      fireEvent.change(await screen.findByLabelText(/วันที่เบิก/), { target: { value: '2026-06-05' } });
      fireEvent.change(screen.getByLabelText(/วันที่ใบเสร็จ/), { target: { value: '2026-05-20' } });
      fireEvent.change(screen.getByLabelText(/จำนวนเสื้อ/), { target: { value: '2' } });
      fireEvent.change(screen.getByLabelText(/จำนวนกางเกง/), { target: { value: '1' } });
      fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'เบิกชุดฟอร์ม' } });
      fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

      await waitFor(() => expect(api.specialMoney.create).toHaveBeenCalledTimes(1));
      const payload = api.specialMoney.create.mock.calls[0][0];
      expect(payload.detail).toEqual({ uniformMode: 'SELF_BUY', shirtCount: '2', trouserCount: '1' });
      expect(typeof payload.detail.shirtCount).toBe('string');
      expect(typeof payload.detail.trouserCount).toBe('string');
    });
  });

  // Plan §"The rule card": generated from the single RULE_CARD table, restating only the
  // selected type's rules.
  describe('rule card', () => {
    it('renders the rule card for the selected type, with cap/frequency/evidence rows', async () => {
      renderPanel();

      await selectType('MEDICAL');
      const card = await screen.findByTestId('rule-card');
      expect(within(card).getByText(/เพดาน/)).not.toBeNull();
      expect(within(card).getByText(/฿3,000/)).not.toBeNull();
      expect(within(card).getByText(/ความถี่/)).not.toBeNull();
      expect(within(card).getByText('ใบเสร็จรับเงินค่ารักษาพยาบาล')).not.toBeNull();
    });

    it('shows the eligibility row only for restrictive types', async () => {
      renderPanel();

      await selectType('MEDICAL');
      let card = await screen.findByTestId('rule-card');
      expect(within(card).queryByText(/ใครเบิกได้/)).toBeNull();

      await selectType('TRAVEL_PER_DIEM');
      card = await screen.findByTestId('rule-card');
      expect(within(card).getByText(/ใครเบิกได้/)).not.toBeNull();
      expect(within(card).getByText(/คนขับ หรือ ผู้ช่วย\/ยกของ/)).not.toBeNull();
    });

    it('flags TRAVEL_PER_DIEM as the one type with no evidence requirement', async () => {
      renderPanel();

      await selectType('TRAVEL_PER_DIEM');
      const card = await screen.findByTestId('rule-card');
      expect(within(card).getByText(/ไม่บังคับแนบหลักฐาน/)).not.toBeNull();
    });
  });

  // Plan §"Type grouping": fixed, rule-derived, common types first.
  it('groups types with the frequent group first and covers every backend type once', () => {
    expect(TYPE_GROUPS[0].label).toBe('เบิกได้บ่อย');
    expect(TYPE_GROUPS[0].types).toEqual(['TRAVEL_PER_DIEM', 'TRAVEL_LODGING', 'MEDICAL']);
    const allTypes = TYPE_GROUPS.flatMap((group) => group.types);
    expect(new Set(allTypes).size).toBe(allTypes.length);
    expect(allTypes).toHaveLength(12);
  });

  it('opens the frequent group by default and keeps others collapsed', async () => {
    renderPanel();

    const picker = await screen.findByLabelText(/ประเภทคำขอ/);
    await waitFor(() => expect(within(picker).queryAllByRole('button').length).toBeGreaterThan(0));
    const toggles = within(picker).getAllByRole('button');
    expect(toggles[0].getAttribute('aria-expanded')).toBe('true');
    expect(toggles.slice(1).every((toggle) => toggle.getAttribute('aria-expanded') === 'false')).toBe(true);
  });

  // Blocker 2 (browser-verified): the disclosure used to render `aria-expanded="false"` while
  // every radio underneath stayed fully visible and tabbable, because a `flex` utility class sat
  // on the panel unconditionally alongside the `hidden` attribute -- Tailwind's `.flex` (utilities
  // layer) beat the legacy stylesheet's `[hidden]{display:none}` (an earlier layer), so the
  // attribute lost the cascade in a real browser. This is the test that "covered" the collapse
  // before (2b): it asserted only `aria-expanded`, which stayed correct throughout, so it passed
  // with the collapse 100% broken.
  //
  // jsdom does not execute Tailwind's generated stylesheet at all, so a real `getComputedStyle`
  // assertion can't distinguish the two versions here -- both would report `display: ''`. What
  // CAN be asserted in jsdom, and is exactly what the real bug looked like at the DOM level, is
  // that the `hidden` attribute and the `flex` class must never both be present on the same
  // panel: that specific co-occurrence is the whole defect. `queryByRole` on the collapsed
  // group's radio is kept too, since RTL's accessibility-tree exclusion (which DOES work in
  // jsdom, driven by the `hidden` IDL attribute, not CSS) is the second thing a real screen
  // reader would also get right or wrong here.
  it('drives a collapsed group panel from state, never combining the hidden attribute with a `flex` class', async () => {
    renderPanel();

    const picker = await screen.findByLabelText(/ประเภทคำขอ/);
    await waitFor(() => expect(within(picker).queryAllByRole('button').length).toBeGreaterThan(0));

    // "อื่นๆ" (OTHER) is the fourth/last group -- collapsed by default.
    expect(within(picker).queryByRole('radio', { name: 'อื่นๆ' })).toBeNull();
    const otherPanel = picker.querySelector('#smr-type-group-panel-OTHER');
    expect(otherPanel).not.toBeNull();
    expect(otherPanel.hasAttribute('hidden')).toBe(true);
    expect(otherPanel.classList.contains('hidden')).toBe(true);
    expect(otherPanel.classList.contains('flex')).toBe(false);

    const toggles = within(picker).getAllByRole('button');
    fireEvent.click(toggles[toggles.length - 1]);

    expect(await within(picker).findByRole('radio', { name: 'อื่นๆ' })).not.toBeNull();
    expect(otherPanel.hasAttribute('hidden')).toBe(false);
    expect(otherPanel.classList.contains('flex')).toBe(true);
    expect(otherPanel.classList.contains('hidden')).toBe(false);
  });

  describe('history list: rejection and attachments', () => {
    it('shows the reviewer note AND the rule card on a rejected row', async () => {
      api.specialMoney.list.mockResolvedValue({ requests: [rejectedRequest()] });
      renderPanel();

      expect(await screen.findByText('ใบเสร็จไม่ชัดเจน กรุณาแนบใหม่')).not.toBeNull();
      expect(await screen.findByTestId('rule-card')).not.toBeNull();
    });

    it('renders the attachment list and offers a download link for a rejected row', async () => {
      api.specialMoney.attachments.mockResolvedValue({
        attachments: [{ id: 501, fileName: 'receipt.pdf', mimeType: 'application/pdf', sizeBytes: 20480 }],
      });
      api.specialMoney.list.mockResolvedValue({ requests: [rejectedRequest()] });
      renderPanel();

      const link = await screen.findByRole('link', { name: /receipt\.pdf/ });
      expect(link.getAttribute('href')).toBe('/api/special-money/attachments/501');
      expect(link.getAttribute('target')).toBe('_blank');
    });

    it('shows an inline attachment list with upload for the requester on their own SUBMITTED request', async () => {
      api.specialMoney.list.mockResolvedValue({ requests: [submittedRequest()] });
      renderPanel();

      await screen.findByText(/พนักงาน ทดสอบ ·/);
      await waitFor(() => expect(api.specialMoney.attachments).toHaveBeenCalledWith(4001));
      // canUpload (requester, still SUBMITTED) renders FileUploadField's own upload affordance.
      expect(await screen.findByText('เลือกไฟล์')).not.toBeNull();
    });
  });

  // feat/pending-approver-info: "who this is waiting on" beside the SUBMITTED/MANAGER_APPROVED
  // status badge -- welfare is CEO-only, single-stage, so the role is always "ceo" here.
  describe('pending-approver note', () => {
    it('renders the note for a SUBMITTED request, and omits it for a REJECTED one', async () => {
      api.specialMoney.list.mockResolvedValue({
        requests: [
          submittedRequest({ pendingApproverRole: 'ceo', pendingApproverName: 'ราม' }),
          rejectedRequest({ pendingApproverRole: null, pendingApproverName: null }),
        ],
      });
      renderPanel();

      // No "รอ" prefix on the note itself (review #pending-approver-info) -- the status badge
      // ("รอ CEO อนุมัติ") already conveys pending-ness, so this bare "CEO (คุณราม)" doesn't
      // duplicate it. The REJECTED row (rejectedRequest, pendingApproverRole: null) renders no
      // such note at all.
      //
      // This is also the case that keeps the sibling test below honest: the note now renders ONLY
      // when a name resolved, and without this assertion that rule could quietly decay into
      // "never render the note" with nothing going red.
      expect(await screen.findByText('CEO (คุณราม)')).not.toBeNull();
    });

    it('drops the approver note entirely when the backend could not resolve a single approver name', async () => {
      api.specialMoney.list.mockResolvedValue({
        requests: [submittedRequest({ pendingApproverRole: 'ceo', pendingApproverName: null })],
      });
      renderPanel();

      const statusColumn = (await screen.findByText(/พนักงาน ทดสอบ ·/))
        .closest('.data-row')
        .querySelector('[data-label="สถานะ"]');

      // This REPLACES an assertion that required a bare "CEO" note here, rather than deleting it:
      // the inverse is now the thing worth pinning. The role-only note was correct when the badge
      // read a generic pending label, but specialMoneyStatusLabel names the role itself now, so
      // the note just repeated the badge -- and with no name it carries nothing the badge cannot
      // already say. Only the badge is left of the "who holds this" story.
      expect(within(statusColumn).getByText('รอ CEO อนุมัติ')).not.toBeNull();
      expect(statusColumn.querySelector('small.text-text-muted')).toBeNull();
      // Scoped to the note's own class deliberately: this fixture (AID_WEDDING, evidenceRequired,
      // no attachment) still renders the separate `small.text-warning` evidence line in this same
      // column, so "the column has no <small> at all" would be the wrong, over-tight assertion.
    });
  });

  // The history table used to call api.specialMoney.list({status}) with no dates at all, which the
  // REAL backend (SpecialMoneyService.list():66-68) silently defaults to "this calendar month" --
  // this fixture's mock does not reproduce that filtering itself (same caveat as the review-queue
  // window test above), so what these pin is that the FRONTEND now always sends an explicit,
  // user-controllable window instead of relying on any default.
  describe('history table period selector', () => {
    it('sends a current-year window by default (ปีนี้)', async () => {
      renderPanel();

      await waitFor(() => expect(api.specialMoney.list).toHaveBeenCalledTimes(1));
      const currentYear = currentBangkokYearForTest();
      expect(api.specialMoney.list).toHaveBeenCalledWith({
        status: undefined,
        from: `${currentYear}-01-01`,
        to: `${currentYear}-12-31`,
      });
    });

    it('widens the window to ~10 years back / 1 year forward when switched to "ทั้งหมด"', async () => {
      renderPanel();
      await waitFor(() => expect(api.specialMoney.list).toHaveBeenCalledTimes(1));

      fireEvent.change(screen.getByLabelText('กรองตามช่วงเวลา'), { target: { value: 'ALL' } });

      await waitFor(() => expect(api.specialMoney.list).toHaveBeenCalledTimes(2));
      const [{ from, to }] = api.specialMoney.list.mock.calls[1];
      // historyWindow's ALL branch builds a local-midnight Date (today's year/month/day +/- N,
      // via the local `getFullYear()`/`getMonth()`/`getDate()` getters) and then stringifies it
      // through `dateIso`, which reformats that instant in Asia/Bangkok via Intl. So this test
      // reproduces BOTH steps exactly -- an expectation built only from local Y/M/D getters (no
      // Bangkok re-render) would itself drift from the real output on any test runner whose system
      // timezone isn't Bangkok, since the component's actual stringification always re-renders in
      // Bangkok regardless of which getters built the Date. Building against `currentYear()`
      // (Bangkok) at the assertion level would separately reintroduce the UTC/Bangkok year-boundary
      // flake this suite exists to prevent. Mirroring the pipeline is the only version immune to
      // both axes at once.
      const bangkokDateIso = (date) => new Intl.DateTimeFormat('en-CA', {
        timeZone: 'Asia/Bangkok', year: 'numeric', month: '2-digit', day: '2-digit',
      }).format(date);
      const today = new Date();
      expect(from).toBe(bangkokDateIso(new Date(today.getFullYear() - 10, today.getMonth(), today.getDate())));
      expect(to).toBe(bangkokDateIso(new Date(today.getFullYear() + 1, today.getMonth(), today.getDate())));
    });

    it('sends the previous-year window when switched to "ปีที่แล้ว"', async () => {
      renderPanel();
      await waitFor(() => expect(api.specialMoney.list).toHaveBeenCalledTimes(1));

      fireEvent.change(screen.getByLabelText('กรองตามช่วงเวลา'), { target: { value: 'LAST_YEAR' } });

      await waitFor(() => expect(api.specialMoney.list).toHaveBeenCalledTimes(2));
      const [{ from, to }] = api.specialMoney.list.mock.calls[1];
      const lastYear = currentBangkokYearForTest() - 1;
      expect(from).toBe(`${lastYear}-01-01`);
      expect(to).toBe(`${lastYear}-12-31`);
    });

    it('names the active period in the empty state, and hints at "ทั้งหมด" while year-scoped', async () => {
      renderPanel();

      const buddhistYear = currentBangkokYearForTest() + 543;
      expect(await screen.findByText(`ยังไม่มีคำขอเงินสวัสดิการในปี ${buddhistYear}`)).not.toBeNull();
      // Unconditional advice, shown on every year-scoped empty result -- a hint pointing at "ทั้งหมด".
      expect(screen.getByText(/ลองเปลี่ยนช่วงเวลาเป็น/)).not.toBeNull();

      fireEvent.change(screen.getByLabelText('กรองตามช่วงเวลา'), { target: { value: 'ALL' } });

      await waitFor(() => expect(screen.queryByText(`ยังไม่มีคำขอเงินสวัสดิการในปี ${buddhistYear}`)).toBeNull());
      expect(await screen.findByText('ยังไม่มีคำขอเงินสวัสดิการ')).not.toBeNull();
      // No period to hint at once "ทั้งหมด" is already selected.
      expect(screen.queryByText(/ลองเปลี่ยนช่วงเวลาเป็น/)).toBeNull();
    });

    // historyPeriodYear must be DERIVED from historyWindow.from, not recomputed independently --
    // otherwise a re-render that crosses the Bangkok New Year (with historyPeriod never touched,
    // so historyWindow itself stays memoized/unchanged) can show a label year one year ahead of
    // the window the query actually ran with.
    it('keeps the empty-state year in sync with the actual query window across a re-render that crosses a Bangkok New Year, without touching the period selector', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      try {
        vi.setSystemTime(new Date('2026-12-31T16:00:00Z')); // 2026-12-31 23:00 Bangkok -- still 2026
        renderPanel();
        await waitFor(() => expect(api.specialMoney.list).toHaveBeenCalledTimes(1));
        expect(await screen.findByText('ยังไม่มีคำขอเงินสวัสดิการในปี 2569')).not.toBeNull();

        // Cross the Bangkok New Year, then force a re-render WITHOUT changing the period
        // selector -- e.g. typing in the unrelated "reason" field re-renders the whole panel via
        // react-hook-form's watch.
        vi.setSystemTime(new Date('2026-12-31T18:00:00Z')); // 2027-01-01 01:00 Bangkok -- now 2027
        fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'x' } });

        // Still 2569: the query never re-ran (historyPeriod, and therefore historyWindow, never
        // changed), so the label describing it must not have silently jumped to 2570 either.
        expect(await screen.findByText('ยังไม่มีคำขอเงินสวัสดิการในปี 2569')).not.toBeNull();
        expect(screen.queryByText('ยังไม่มีคำขอเงินสวัสดิการในปี 2570')).toBeNull();
        expect(api.specialMoney.list).toHaveBeenCalledTimes(1);
      } finally {
        vi.useRealTimers();
      }
    });
  });

  // SpecialMoneyPanel builds the history table's cache-key filters with a BARE `statusFilter`
  // ('' when unset), while the CEO review queue's key comes from `reviewQueueWindow` -- a plain
  // {from,to} object with no `status` property at all, so queryKeys.specialMoneyRequests() reads
  // its `filters.status` as `undefined`. Under historyPeriod 'ALL' with no status filter, the two
  // queries' date windows are the SAME 10-years-back/1-year-forward shape, so '' vs `undefined` at
  // that one key slot is the only thing keeping their react-query cache entries apart. This does
  // not render the component -- it tests queryKeys.specialMoneyRequests() directly against the
  // exact filter shapes SpecialMoneyPanel constructs, which is what actually protects the
  // invariant (see the comment on `filters` in SpecialMoneyPanel.jsx).
  describe('history/review-queue cache-key separation', () => {
    // Documents the exact key shapes involved (pure, no render) -- pins that '' and `undefined`
    // serialize differently at the `status` slot for every window shape the two queries can take.
    // This alone does NOT catch a regression in SpecialMoneyPanel's own `filters` construction
    // (e.g. someone "cleaning up" `status: statusFilter` to `status: statusFilter || undefined`)
    // since it never touches the component -- see the real-wiring test below for that.
    it('keeps every historyPeriod x status combination distinct from the review queue key', () => {
      // Mirrors reviewQueueWindow's shape: no `status` property at all.
      const reviewQueueWindow = { from: '2016-08-03', to: '2027-08-03' };
      const reviewQueueKey = JSON.stringify(queryKeys.specialMoneyRequests(reviewQueueWindow));

      const historyWindows = [
        { from: '2026-01-01', to: '2026-12-31' }, // THIS_YEAR-shaped
        { from: '2025-01-01', to: '2025-12-31' }, // LAST_YEAR-shaped
        reviewQueueWindow, // ALL-shaped -- identical window to the queue; the risky combination
      ];
      const statuses = ['', 'SUBMITTED', 'MANAGER_APPROVED', 'APPROVED', 'REJECTED', 'CANCELLED'];

      for (const window of historyWindows) {
        for (const status of statuses) {
          // Mirrors SpecialMoneyPanel's `filters` object exactly: bare `status`, never
          // `status || undefined`.
          const historyKey = JSON.stringify(queryKeys.specialMoneyRequests({ status, from: window.from, to: window.to }));
          expect(historyKey).not.toBe(reviewQueueKey);
        }
      }
    });

    // The real guard: exercises SpecialMoneyPanel's ACTUAL `filters` useMemo and the ACTUAL
    // reviewQueueQuery side by side, as a CEO, under "ทั้งหมด" + no status -- the one combination
    // where their date windows become identical. If the two queries' cache keys ever collided
    // (e.g. from the "cleanup" the comment on `filters` warns against), react-query would treat
    // them as the SAME query: the history table's switch to "ทั้งหมด" would just reuse the review
    // queue's already-cached result instead of firing its own fetch, so the THIRD
    // api.specialMoney.list call below would never happen.
    // Inspects the QueryClient's cache directly rather than counting api.specialMoney.list calls:
    // an earlier version of this test counted fetches instead, and a mutation-check proved it
    // vacuous -- react-query's own refetch-on-mount/staleness behaviour means a THIRD fetch fires
    // when switching to "ทั้งหมด" whether or not the two keys actually collide (switching away
    // from the THIS_YEAR window is by itself enough to trigger it), so call-counting alone cannot
    // tell separate-but-both-fetched apart from collided-and-both-fetched. Counting distinct cache
    // ENTRIES can: a collision merges two observers onto one Query object, so the cache holds one
    // fewer ['specialMoney','list',...] entry than it should.
    it('keeps the history table\'s "ทั้งหมด" query and the review queue as separate cache entries (real component wiring)', async () => {
      api.specialMoney.list.mockResolvedValue({ requests: [] });
      const { queryClient } = renderPanel(ceoUser);

      await waitFor(() => expect(api.specialMoney.list).toHaveBeenCalledTimes(2));

      fireEvent.change(await screen.findByLabelText('กรองตามช่วงเวลา'), { target: { value: 'ALL' } });
      await waitFor(() => expect(api.specialMoney.list).toHaveBeenCalledTimes(3));

      const specialMoneyListEntries = queryClient.getQueryCache()
        .findAll({ queryKey: ['specialMoney', 'list'] });
      // THIS_YEAR (now stale/inactive but still cached), 'ALL'+no-status, and reviewQueueWindow --
      // three distinct entries. If the history table's 'ALL' key had collided with
      // reviewQueueWindow's, there would only be two.
      expect(specialMoneyListEntries).toHaveLength(3);
    });
  });

  // Welfare is CEO-only in one stage. These pin the button gating, which is the half of that rule
  // a user actually meets -- the server-side half is SpecialMoneyScopeIntegrationTest's job.
  describe('approval gating', () => {
    beforeEach(() => {
      api.specialMoney.list.mockResolvedValue({ requests: [submittedRequest()] });
    });

    it('offers the CEO an approve button in the review queue on a SUBMITTED request', async () => {
      renderPanel(ceoUser);

      expect(await screen.findByRole('button', { name: 'CEO อนุมัติ' })).toBeTruthy();
    });

    it('offers a ฝ่าย manager no approve button, even for their own division', async () => {
      // The tempting wrong answer: overtime DOES let a ฝ่าย manager approve, and this panel used
      // to as well. Welfare has no manager stage at all, so the button must not appear.
      renderPanel(managerUser);

      await screen.findByText(/พนักงาน ทดสอบ ·/);
      expect(screen.queryByRole('button', { name: 'CEO อนุมัติ' })).toBeNull();
      expect(screen.queryByRole('button', { name: /ผู้จัดการอนุมัติ/ })).toBeNull();
    });

    it('offers the requester no approve button on their own request', async () => {
      renderPanel();

      await screen.findByText(/พนักงาน ทดสอบ ·/);
      expect(screen.queryByRole('button', { name: 'CEO อนุมัติ' })).toBeNull();
    });

    // The redesign's core defect fix: the CEO must see evidence, and be warned before they can
    // approve an evidence-required type with nothing attached.
    it('warns the CEO and disables approve when an evidence-required type has no attachment', async () => {
      api.specialMoney.list.mockResolvedValue({
        requests: [submittedRequest({ requestType: 'AID_WEDDING', attachmentCount: 0 })],
      });
      renderPanel(ceoUser);

      const approveButton = await screen.findByRole('button', { name: 'CEO อนุมัติ' });
      // The same warning also appears on the request's row in the "My requests" history table
      // below (unchanged pre-existing behaviour there), so this scopes to the review queue card
      // that actually holds the approve button rather than asserting on the page as a whole.
      const reviewCard = approveButton.closest('.py-3\\.5');
      expect(within(reviewCard).getByText(/ยังไม่ได้แนบเอกสาร — อนุมัติไม่ได้/)).not.toBeNull();
      expect(approveButton.disabled).toBe(true);
    });

    it('does not block approve for TRAVEL_PER_DIEM, the one type with no evidence requirement', async () => {
      api.specialMoney.list.mockResolvedValue({
        requests: [submittedRequest({ requestType: 'TRAVEL_PER_DIEM', attachmentCount: 0 })],
      });
      renderPanel(ceoUser);

      const approveButton = await screen.findByRole('button', { name: 'CEO อนุมัติ' });
      expect(screen.queryByText(/ยังไม่ได้แนบเอกสาร — อนุมัติไม่ได้/)).toBeNull();
      expect(approveButton.disabled).toBe(false);
    });

    // HIGH 3 (browser/API-contract fix): the real backend defaults `from`/`to` to the CURRENT
    // CALENDAR MONTH when a list() call omits them (SpecialMoneyService.list():66-68). A bare
    // `list({status})` call for the review queue would therefore silently lose any pending
    // request whose event_date falls outside "this month" once it hit the real service -- this
    // mock can't reproduce that filtering itself (mockApi's list() never filters by from/to
    // unless the caller passes them), so the meaningful assertion here is that the FRONTEND now
    // actually sends a wide date window for the review queue, wide enough to cover an event date
    // years in the past (aid claims carry no filing deadline).
    it('fetches the CEO review queue with an explicit wide date window, not the bare call the backend would default to "this month"', async () => {
      const oldEventRequest = submittedRequest({ id: 4010, eventDate: '2020-01-15', requestType: 'AID_FUNERAL' });
      api.specialMoney.list.mockResolvedValue({ requests: [oldEventRequest] });
      renderPanel(ceoUser);

      await screen.findByRole('button', { name: 'CEO อนุมัติ' });
      // The history table below now ALSO sends an explicit `from`/`to` (its own period selector,
      // defaulting to the current year) -- distinguish the review queue's call by the absence of a
      // `status` key, which only the history table's query ever sends (even as `undefined`).
      const wideWindowCall = api.specialMoney.list.mock.calls.find(([params]) => params && params.from && !('status' in params));
      expect(wideWindowCall).toBeTruthy();
      const [{ from, to }] = wideWindowCall;
      expect(from <= '2020-01-15').toBe(true);
      expect(to >= '2020-01-15').toBe(true);
    });
  });

  // MEDIUM 4/5: fields the evaluator actually reads that the form previously had no way to set.
  describe('uniform form fields the evaluator reads', () => {
    it('UNIFORM_PREPROBATION_KIT: sends needsBackSupport as a STRING, and only that key', async () => {
      renderPanel();

      await selectType('UNIFORM_PREPROBATION_KIT');
      fireEvent.change(await screen.findByLabelText(/วันที่เบิก/), { target: { value: '2026-07-10' } });
      fireEvent.click(screen.getByRole('checkbox', { name: /สายรัดหลัง/ }));
      fireEvent.change(screen.getByLabelText(/จำนวนเงินที่ขอเบิก/), { target: { value: '2660' } });
      fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ของเดิมไม่พอ' } });
      fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

      await waitFor(() => expect(api.specialMoney.create).toHaveBeenCalledTimes(1));
      const payload = api.specialMoney.create.mock.calls[0][0];
      expect(payload.detail).toEqual({ needsBackSupport: 'true' });
      expect(typeof payload.detail.needsBackSupport).toBe('string');
    });

    it('UNIFORM_ANNUAL TAILORED mode: does not require a receipt date, and sends the chosen mode', async () => {
      renderPanel();

      await selectType('UNIFORM_ANNUAL');
      fireEvent.change(await screen.findByLabelText(/วิธีเบิก/), { target: { value: 'TAILORED' } });
      expect(screen.queryByLabelText(/วันที่ใบเสร็จ/)).toBeNull();
      fireEvent.change(screen.getByLabelText(/วันที่เบิก/), { target: { value: '2026-06-15' } });
      fireEvent.change(screen.getByLabelText(/จำนวนเสื้อ/), { target: { value: '1' } });
      fireEvent.change(screen.getByLabelText(/จำนวนกางเกง/), { target: { value: '1' } });
      fireEvent.change(screen.getByLabelText(/จำนวนเงินที่ขอเบิก/), { target: { value: '900' } });
      fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ตัดชุดกับร้าน' } });
      fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

      await waitFor(() => expect(api.specialMoney.create).toHaveBeenCalledTimes(1));
      const payload = api.specialMoney.create.mock.calls[0][0];
      expect(payload.detail.uniformMode).toBe('TAILORED');
      expect(payload.receiptDate).toBeNull();
      expect(payload.requestedAmount).toBe(900);
    });
  });

  // MEDIUM 6: submit errors arrive as one 400 string with violations joined by "; ".
  it('splits a joined submit error into a rendered list instead of one run-on sentence', async () => {
    api.specialMoney.create.mockRejectedValueOnce(
      new Error('พนักงานยังไม่พ้นทดลองงาน; ต้องแนบเอกสารหลักฐาน; จำนวนเงินเกินเพดาน'),
    );
    renderPanel();

    await selectType('AID_WEDDING');
    fireEvent.change(await screen.findByLabelText(/วันที่เกิดเหตุการณ์/), { target: { value: '2026-07-01' } });
    fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    expect(await screen.findByText('พนักงานยังไม่พ้นทดลองงาน')).not.toBeNull();
    expect(screen.getByText('ต้องแนบเอกสารหลักฐาน')).not.toBeNull();
    expect(screen.getByText('จำนวนเงินเกินเพดาน')).not.toBeNull();
    // Never rendered as one joined sentence.
    expect(screen.queryByText(/พ้นทดลองงาน; ต้องแนบ/)).toBeNull();
  });

  // Violations describe the type that was submitted. Leaving them up after the employee switches
  // type tells them they broke rules on a claim they are no longer making -- and the wedding
  // violations below are impossible for MEDICAL, so they are not merely stale but wrong.
  it('clears submit violations when the employee switches to a different request type', async () => {
    api.specialMoney.create.mockRejectedValueOnce(
      new Error('พนักงานยังไม่พ้นทดลองงาน; ต้องแนบเอกสารหลักฐาน'),
    );
    renderPanel();

    await selectType('AID_WEDDING');
    fireEvent.change(await screen.findByLabelText(/วันที่เกิดเหตุการณ์/), { target: { value: '2026-07-01' } });
    fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    // Assert they were actually on screen first -- otherwise the disappearance below proves
    // nothing, because they may never have rendered at all.
    expect(await screen.findByText('พนักงานยังไม่พ้นทดลองงาน')).not.toBeNull();

    await selectType('MEDICAL');

    await waitFor(() => expect(screen.queryByText('พนักงานยังไม่พ้นทดลองงาน')).toBeNull());
    expect(screen.queryByText('ต้องแนบเอกสารหลักฐาน')).toBeNull();
  });

  // The submit button fires TWO requests -- POST /special-money, then POST /{id}/attachments --
  // and they can fail independently. Production 2026-08-31: rows 1 and 2 were filed, audited and
  // notified while the upload answered 500, and the panel reported "ส่งคำขอไม่สำเร็จ" for both.
  describe('a request that is filed but whose evidence upload fails', () => {
    function evidence() {
      return new File(['x'], 'marriage-cert.pdf', { type: 'application/pdf' });
    }

    async function submitWeddingWithEvidence() {
      await selectType('AID_WEDDING');
      fireEvent.change(await screen.findByLabelText(/วันที่เกิดเหตุการณ์/), { target: { value: '2026-07-01' } });
      fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ทดสอบระบบ' } });
      fireEvent.change(document.getElementById('smr-evidence'), { target: { files: [evidence()] } });
      fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));
    }

    it('reports the request as filed instead of reporting the whole submit as failed', async () => {
      api.specialMoney.addAttachment.mockRejectedValueOnce(new Error('เกิดข้อผิดพลาดภายในระบบ'));
      const showToast = vi.fn();
      renderPanel(user, showToast);

      await submitWeddingWithEvidence();

      expect(await screen.findByText(/บันทึกคำขอเลขที่ 3001 เรียบร้อยแล้ว/)).not.toBeNull();
      expect(screen.getByText(/แต่แนบเอกสารหลักฐานไม่สำเร็จ: เกิดข้อผิดพลาดภายในระบบ/)).not.toBeNull();
      // The lie this removes: the upload's 500 used to render under "ส่งคำขอไม่สำเร็จ:", telling
      // the employee nothing had been filed while the row existed.
      expect(screen.queryByText('ส่งคำขอไม่สำเร็จ:')).toBeNull();
      expect(showToast.mock.calls.some(([kind]) => kind === 'error')).toBe(false);
    });

    it('clears the form, so pressing ส่งคำขอ again cannot re-file the accepted payload', async () => {
      api.specialMoney.addAttachment.mockRejectedValueOnce(new Error('เกิดข้อผิดพลาดภายในระบบ'));
      renderPanel();

      await submitWeddingWithEvidence();
      await screen.findByText(/บันทึกคำขอเลขที่ 3001 เรียบร้อยแล้ว/);

      // Both halves matter: the notice says "ไม่ต้องส่งคำขอซ้ำ", and the form no longer holds the
      // data that would make sending it again a single click.
      expect(screen.getByText(/ไม่ต้องส่งคำขอซ้ำ/)).not.toBeNull();
      expect(screen.getByLabelText(/เหตุผล/).value).toBe('');
      expect(api.specialMoney.create).toHaveBeenCalledTimes(1);
    });

    it('retries the upload alone, against the request that already exists', async () => {
      api.specialMoney.addAttachment.mockRejectedValueOnce(new Error('เกิดข้อผิดพลาดภายในระบบ'));
      renderPanel();

      await submitWeddingWithEvidence();
      await screen.findByText(/บันทึกคำขอเลขที่ 3001 เรียบร้อยแล้ว/);

      api.specialMoney.addAttachment.mockResolvedValueOnce({ attachment: { id: 9001 } });
      fireEvent.click(screen.getByRole('button', { name: /ลองแนบอีกครั้ง/ }));

      await waitFor(() => expect(api.specialMoney.addAttachment).toHaveBeenCalledTimes(2));
      const [retriedId, retriedFile] = api.specialMoney.addAttachment.mock.calls[1];
      expect(retriedId).toBe(3001);
      // The SAME file the employee already chose -- a retry that made them re-pick it is the
      // reason re-filing the whole request looked like the shorter path.
      expect(retriedFile.name).toBe('marriage-cert.pdf');
      // And no second request was filed to carry it.
      expect(api.specialMoney.create).toHaveBeenCalledTimes(1);
      await waitFor(() => expect(screen.queryByText(/บันทึกคำขอเลขที่ 3001 เรียบร้อยแล้ว/)).toBeNull());
    });

    it('keeps the notice up, with the newer reason, when the retry fails too', async () => {
      api.specialMoney.addAttachment.mockRejectedValueOnce(new Error('เกิดข้อผิดพลาดภายในระบบ'));
      renderPanel();

      await submitWeddingWithEvidence();
      await screen.findByText(/บันทึกคำขอเลขที่ 3001 เรียบร้อยแล้ว/);

      api.specialMoney.addAttachment.mockRejectedValueOnce(new Error('ไฟล์มีขนาดใหญ่เกินไป'));
      fireEvent.click(screen.getByRole('button', { name: /ลองแนบอีกครั้ง/ }));

      expect(await screen.findByText(/แต่แนบเอกสารหลักฐานไม่สำเร็จ: ไฟล์มีขนาดใหญ่เกินไป/)).not.toBeNull();
      // The request-was-filed fact must survive a failed retry -- a toast alone disappears after
      // 3.2s and leaves nothing on screen saying the request exists.
      expect(screen.getByText(/บันทึกคำขอเลขที่ 3001 เรียบร้อยแล้ว/)).not.toBeNull();
    });

    // The other direction, so the change above cannot be satisfied by never reporting a failure.
    it('still reports a genuine create failure as a failed submit, with the form intact', async () => {
      api.specialMoney.create.mockRejectedValueOnce(new Error('พนักงานยังไม่พ้นทดลองงาน'));
      renderPanel();

      await submitWeddingWithEvidence();

      expect(await screen.findByText('ส่งคำขอไม่สำเร็จ:')).not.toBeNull();
      expect(screen.getByText('พนักงานยังไม่พ้นทดลองงาน')).not.toBeNull();
      // Nothing was filed, so there is no request to point at and the upload never runs.
      expect(screen.queryByText(/บันทึกคำขอเลขที่/)).toBeNull();
      expect(api.specialMoney.addAttachment).not.toHaveBeenCalled();
      // And what was typed survives, because correcting and resubmitting IS the right move here.
      expect(screen.getByLabelText(/เหตุผล/).value).toBe('ทดสอบระบบ');
    });
  });
});
