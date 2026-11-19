import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LeaveRequestPage } from './LeaveRequestPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    leave: {
      employees: vi.fn(),
      types: vi.fn(),
      list: vi.fn(),
      balances: vi.fn(),
      contactDefaults: vi.fn(),
      create: vi.fn(),
      cancel: vi.fn(),
      preview: vi.fn(),
      // Step 2 reads this unconditionally (the วันหยุดที่จะถึง panel AND the per-selection
      // calendar-context note both call it), so every test that reaches step 2 needs a default
      // resolved value, or both queries error out silently.
      calendarContext: vi.fn(),
    },
  },
}));

// browser-image-compression returns a plain Blob with no `.name`; this mock reproduces that rather
// than a File, which is the shape that exposed the "blob" filename bug. A mock that quietly
// upgraded the return type would pass whether or not the component re-wraps it.
vi.mock('browser-image-compression', () => ({
  default: vi.fn((file) => Promise.resolve(new Blob([file], { type: file.type }))),
}));

const user = { employeeId: 1, name: 'พนักงาน ทดสอบ', role: 'employee', manager: false };
const currentEmployee = { id: 1, nameTh: 'พนักงาน ทดสอบ' };

const emptyContactDefaults = {
  contactDefaults: {
    employeeId: 1, positionTh: null, departmentTh: null, divisionTh: null,
    contactHouseNo: null, contactSubdistrict: null, contactDistrict: null, contactProvince: null, contactPhone: null,
  },
};

const NO_COUNTERS = { emergencyFilingsRemaining: 3, noCertificateOccasionsRemaining: 3 };

function approvedPreview({ depth } = {}) {
  return {
    preview: {
      blocking: null,
      datesEvaluated: true,
      coverageEvaluated: depth === 'FULL',
      totalDays: 1,
      paidDays: 1,
      unpaidDays: 0,
      quotaYearSplits: [{
        quotaYear: 2099, totalDays: 1, paidDays: 1, unpaidDays: 0, quotaRemainingBefore: 6, quotaRemainingAfter: 5,
      }],
      counters: NO_COUNTERS,
    },
  };
}

const dateless_ok_preview = {
  preview: {
    blocking: null, datesEvaluated: false, coverageEvaluated: false,
    totalDays: null, paidDays: null, unpaidDays: null, quotaYearSplits: [], counters: NO_COUNTERS,
  },
};

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

function renderComposer(initialEntries = ['/leave/new']) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const utils = render(
    <MemoryRouter initialEntries={initialEntries}>
      <LocationProbe />
      <QueryClientProvider client={queryClient}>
        <LeaveRequestPage user={user} currentEmployee={currentEmployee} showToast={vi.fn()} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return { ...utils, queryClient };
}

async function goToStep2ForVacation() {
  // Returns the render utils (container, etc.) so callers can reach past the rendered output, e.g.
  // dispatching a raw DOM event straight at the <form>.
  const utils = renderComposer();
  const vacationButton = await screen.findByRole('button', { name: /ลาพักร้อน/ });
  fireEvent.click(vacationButton);
  fireEvent.click(screen.getByRole('button', { name: 'ถัดไป' }));
  await screen.findByText(/ขั้นตอนที่ 2\/3/);
  return utils;
}

// Shared baseline fixture, called from every describe block's own `beforeEach`. Each block calling this itself and clearing
// mocks itself keeps every block correct regardless of what ran before it: `vi.fn()`
// implementations set via `mockResolvedValue` persist across tests (only `vi.clearAllMocks()`,
// called here, resets call HISTORY), so test ORDER must never be load-bearing for isolation.
function installDefaultLeaveMocks() {
  vi.clearAllMocks();
  api.leave.employees.mockResolvedValue({
    employees: [{
      employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001', self: true, directReport: false,
    }],
  });
  api.leave.types.mockResolvedValue({
    leaveTypes: [
      { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation', annualQuotaDays: 6 },
      { code: 'SICK', nameTh: 'ลาป่วย', nameEn: 'Sick', annualQuotaDays: 30 },
      { code: 'PERSONAL', nameTh: 'ลากิจ', nameEn: 'Personal', annualQuotaDays: 7 },
    ],
  });
  api.leave.balances.mockResolvedValue({ balances: [] });
  api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
  api.leave.create.mockResolvedValue({ request: { id: 2001, status: 'SUBMITTED' } });
  api.leave.preview.mockImplementation((payload) => Promise.resolve(
    payload?.startDate ? approvedPreview(payload) : dateless_ok_preview,
  ));
  // Default: no holidays anywhere -- keeps every test below on the empty-state วันหยุดที่จะถึง panel and the
  // "all working days" calendar-context note, uninvolved with what each test actually asserts on. The
  // holiday-visibility cases below override this per-test.
  api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
}

describe('LeaveRequestPage (Phase A2, #485)', () => {
  beforeEach(() => {
    installDefaultLeaveMocks();
  });

  it('step 1: a categorically-blocked type is disabled and shows the real backend reason', async () => {
    const blockedOutcome = {
      code: 'ONCE_PER_EMPLOYMENT',
      params: { leaveTypeNameTh: 'ลาอุปสมบท' },
      messageTh: 'การลาอุปสมบทสามารถใช้สิทธิ์ได้เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน',
    };
    api.leave.types.mockResolvedValue({
      leaveTypes: [
        { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation', annualQuotaDays: 6 },
        { code: 'ORDINATION', nameTh: 'ลาอุปสมบท', nameEn: 'Ordination', oncePerEmployment: true, annualQuotaDays: 60 },
      ],
    });
    api.leave.preview.mockImplementation((payload) => {
      if (payload?.leaveTypeCode === 'ORDINATION') {
        return Promise.resolve({
          preview: {
            blocking: blockedOutcome, datesEvaluated: false, coverageEvaluated: false,
            totalDays: null, paidDays: null, unpaidDays: null, quotaYearSplits: [], counters: NO_COUNTERS,
          },
        });
      }
      return Promise.resolve(dateless_ok_preview);
    });

    renderComposer();
    fireEvent.click(await screen.findByRole('button', { name: /ประเภทอื่นๆ/ }));

    // Two buttons match /ลาอุปสมบท/: the "ประเภทอื่นๆ" disclosure toggle (its subtitle lists "ลาคลอด ลาทหาร
    // ลาอุปสมบท") and the actual type-choice button -- disambiguate by picking the one that is itself the disabled
    // type button, not the toggle.
    await waitFor(() => {
      const matches = screen.getAllByRole('button', { name: /ลาอุปสมบท/ });
      const ordinationButton = matches.find((button) => button.hasAttribute('disabled'));
      expect(ordinationButton).toBeTruthy();
    });
    expect(await screen.findByText(/ใช้สิทธิ์ได้เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน/)).not.toBeNull();
  });

  it('step 2: start date before today is rejected and cannot advance to step 3', async () => {
    await goToStep2ForVacation();

    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: '2020-01-01' } });
    fireEvent.blur(screen.getByLabelText(/วันที่เริ่ม/));
    expect(await screen.findByText('วันที่เริ่มลาต้องไม่ก่อนวันนี้')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    expect(screen.queryByText(/ขั้นตอนที่ 3\/3/)).toBeNull();
  });

  // "ลาป่วยย้อนหลังได้ตลอด พนักงานยื่นได้": unlike every other type above, SICK must NOT trip the
  // past-start-date block at all, with no lower bound. This does not exercise
  // sickCertificateRuleOutcome's separate SICK_CERTIFICATE_WINDOW gate (LeaveService.java), which
  // lives in the backend preview response.
  it('step 2: SICK start date in the past is NOT rejected and does not block advancing', async () => {
    renderComposer();
    fireEvent.click(await screen.findByRole('button', { name: /ลาป่วย/ }));
    fireEvent.click(screen.getByRole('button', { name: 'ถัดไป' }));
    await screen.findByText(/ขั้นตอนที่ 2\/3/);

    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: '2020-01-01' } });
    fireEvent.blur(screen.getByLabelText(/วันที่เริ่ม/));
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ท้องเสีย + ปวดหัวไมเกรน' } });
    expect(screen.queryByText('วันที่เริ่มลาต้องไม่ก่อนวันนี้')).toBeNull();

    // Both guards, not just the visible one: `disabled` on the button AND advanceFromStep2's own `if (!valid ||
    // startDateInPast) return`. Asserting only the attribute would stay green if a second, independent past-date
    // check were ever added inside the handler.
    expect(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    expect(await screen.findByText(/ขั้นตอนที่ 3\/3/)).not.toBeNull();
  });

  // PERSONAL filed as ลากิจฉุกเฉิน is exempt from the past-start-date block the same way SICK is
  // above, but gated on the checkbox itself: ticking it IS the "yes, this is an emergency"
  // confirmation. See isStartDateBlockedAsPast's own comment for why the backend needs no matching
  // change.
  it('step 2: PERSONAL start date in the past is blocked until ลากิจฉุกเฉิน is ticked, then advances to step 3', async () => {
    renderComposer();
    // No "ประเภทอื่นๆ" disclosure needed -- ลากิจ (PERSONAL) is a top-level type button, same as the "preview call
    // that fails outright" test above confirms with the same query.
    fireEvent.click(await screen.findByRole('button', { name: /ลากิจ/ }));
    fireEvent.click(screen.getByRole('button', { name: 'ถัดไป' }));
    await screen.findByText(/ขั้นตอนที่ 2\/3/);

    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: '2020-01-01' } });
    fireEvent.blur(screen.getByLabelText(/วันที่เริ่ม/));
    // Anchored so this doesn't ALSO match PERSONAL's own "เหตุผลการลากิจ (ถ้ามี)" select, which shares the same
    // leading substring -- a plain /เหตุผลการลา/ is ambiguous only on this type.
    fireEvent.change(screen.getByLabelText(/^เหตุผลการลา(?!กิจ)/), { target: { value: 'ธุระด่วนที่บ้าน' } });
    expect(await screen.findByText('วันที่เริ่มลาต้องไม่ก่อนวันนี้ หากเป็นลากิจฉุกเฉิน ให้เลือก “ลากิจฉุกเฉิน” ด้านล่างก่อน')).not.toBeNull();
    expect(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }).disabled).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    expect(screen.queryByText(/ขั้นตอนที่ 3\/3/)).toBeNull();

    fireEvent.click(screen.getByLabelText(/ลากิจฉุกเฉิน/));
    await waitFor(() => expect(screen.queryByText(/วันที่เริ่มลาต้องไม่ก่อนวันนี้/)).toBeNull());
    expect(screen.getByLabelText(/วันที่เริ่ม/).getAttribute('aria-invalid')).not.toBe('true');
    expect(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }).disabled).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    expect(await screen.findByText(/ขั้นตอนที่ 3\/3/)).not.toBeNull();

    await waitFor(() => expect(api.leave.preview).toHaveBeenCalledWith(
      expect.objectContaining({ leaveTypeCode: 'PERSONAL', startDate: '2020-01-01', requestedAsEmergency: true }),
      expect.anything(),
    ));
  });

  it('full happy path: sends the same create() payload shape the pre-A2 form sent, via step 3', async () => {
    await goToStep2ForVacation();

    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.blur(screen.getByLabelText(/วันที่เริ่ม/));
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบระบบ' } });

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    await screen.findByText(/ขั้นตอนที่ 3\/3/);

    const submitButton = await screen.findByRole('button', { name: /ส่งคำขอ/ });
    await waitFor(() => expect(submitButton.disabled).toBe(false));
    fireEvent.click(submitButton);

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
    expect(api.leave.create).toHaveBeenCalledWith({
      employeeId: 1,
      leaveTypeCode: 'VACATION',
      startDate: futureDate,
      endDate: futureDate,
      reason: 'ทดสอบระบบ',
      startTime: null,
      endTime: null,
      contactHouseNo: null,
      contactSubdistrict: null,
      contactDistrict: null,
      contactProvince: null,
      contactPhone: null,
      purposeCode: null,
      requestedAsEmergency: null,
      // Pool choice: always sent, defaulting to CARRIED_IN_FIRST. This fixture's balances()
      // resolves to `[]` (beforeEach), so the selector never renders and the value here is untouched
      // defaultForm() state, not a user choice. See the "quota pool preference" describe block below
      // for the selector's own coverage.
      quotaPoolPreference: 'CARRIED_IN_FIRST',
      attachmentFile: null,
    });
  });

  it('step 3: a blocking verdict renders role="alert" and disables submit', async () => {
    const blockingOutcome = {
      code: 'DEPARTMENT_COVERAGE',
      params: { uncoveredDate: '2099-12-31' },
      messageTh: 'ไม่มีพนักงานคนอื่นในแผนกทำงานในวันที่เลือก',
    };
    api.leave.preview.mockImplementation((payload) => {
      if (!payload?.startDate) return Promise.resolve(dateless_ok_preview);
      if (payload.depth === 'FULL') {
        return Promise.resolve({
          preview: {
            blocking: blockingOutcome, datesEvaluated: true, coverageEvaluated: true,
            totalDays: 1, paidDays: null, unpaidDays: null, quotaYearSplits: [], counters: NO_COUNTERS,
          },
        });
      }
      return Promise.resolve(approvedPreview(payload));
    });

    await goToStep2ForVacation();
    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.blur(screen.getByLabelText(/วันที่เริ่ม/));
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/ไม่มีพนักงานคนอื่นในแผนกทำงานในวันที่เลือก/);
    expect(screen.getByRole('button', { name: /ส่งคำขอ/ }).disabled).toBe(true);
  });

  // The carry-in/own-quota selector. Nested (not a sibling describe) so it inherits the outer
  // beforeEach's employees/contactDefaults/create/preview/calendarContext mocks; only leaveTypes
  // (VACATION gains carriesForward: true below) and balances (a real carry-in fixture) differ per test.
  describe('quota-pool preference (§5.3.5, V161)', () => {
    beforeEach(() => {
      api.leave.types.mockResolvedValue({
        leaveTypes: [
          { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation', annualQuotaDays: 6, carriesForward: true },
          { code: 'SICK', nameTh: 'ลาป่วย', nameEn: 'Sick', annualQuotaDays: 30 },
          { code: 'PERSONAL', nameTh: 'ลากิจ', nameEn: 'Personal', annualQuotaDays: 7 },
        ],
      });
    });

    function carryInBalance(overrides = {}) {
      return {
        balances: [{
          leaveTypeCode: 'VACATION',
          leaveTypeNameTh: 'ลาพักร้อน',
          annualQuotaDays: 6,
          approvedDays: 0,
          pendingDays: 0,
          remainingDays: 8,
          carriedInDays: 2,
          carriedInFromYear: 2025,
          carriedInExpiresOn: '2026-12-31',
          carriedInRemainingDays: 2,
          ownQuotaRemainingDays: 6,
          ...overrides,
        }],
      };
    }

    // Step-3 QuotaBar with carry-forward: the counterpart to MyLeaveTab.test.jsx's "reconciles
    // สิทธิ์/เหลือ and the QuotaBar cap when a carry-over is present". A 6-day annual VACATION quota
    // plus a 2-day carry-in, one request for 7 days: 7 > 6 (the bare annualQuotaDays) but inside
    // 6 + 2 = 8 (the combined pool), so the bar must read the request as fitting.
    it("step 3's QuotaBar cap folds in carriedInDays, so a request within the combined pool does not fire the over-quota warning", async () => {
      api.leave.balances.mockResolvedValue(carryInBalance());
      // Overrides the default installDefaultLeaveMocks() preview (fixed totalDays: 1) -- both the step-2 QUICK and
      // step-3 FULL calls resolve through this, but only step 3's FULL result reaches the QuotaBar
      // (`step3Preview.totalDays`), which is all this test reads.
      api.leave.preview.mockImplementation((payload) => Promise.resolve({
        preview: {
          blocking: null,
          datesEvaluated: true,
          coverageEvaluated: payload?.depth === 'FULL',
          totalDays: 7,
          paidDays: 7,
          unpaidDays: 0,
          quotaYearSplits: [],
          counters: NO_COUNTERS,
        },
      }));
      await goToStep2ForVacation();

      const futureDate = '2099-12-20';
      fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
      fireEvent.blur(screen.getByLabelText(/วันที่เริ่ม/));
      fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบระบบ' } });
      fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
      await screen.findByText(/ขั้นตอนที่ 3\/3/);

      const progressBar = await screen.findByRole('progressbar');
      // cap is annualQuotaDays (6) + carriedInDays (2) = 8, not the bare 6 -- used (7, this request's own totalDays)
      // no longer exceeds it.
      const quotaBarRoot = progressBar.parentElement;
      expect(quotaBarRoot.textContent).toMatch(/7\u00A0วัน \/ 8\u00A0วัน/);
      expect(quotaBarRoot.textContent).not.toMatch(/เกินโควตาประจำปี/);
      expect(screen.queryByText(/เกินโควตาประจำปี/)).toBeNull();
      expect(progressBar.getAttribute('aria-valuenow')).toBe('88');
      expect(progressBar.getAttribute('aria-valuemax')).toBe('100');
    });

    // Wrong-way-round, on purpose: a request that genuinely exceeds the COMBINED pool (annualQuotaDays +
    // carriedInDays = 8) must still warn -- proves the carry-in fold-in only narrows the false positive above and
    // does not silently disable the real check.
    it("step 3's QuotaBar still fires the over-quota warning once a request exceeds annualQuotaDays + carriedInDays", async () => {
      api.leave.balances.mockResolvedValue(carryInBalance());
      api.leave.preview.mockImplementation((payload) => Promise.resolve({
        preview: {
          blocking: null,
          datesEvaluated: true,
          coverageEvaluated: payload?.depth === 'FULL',
          totalDays: 9,
          paidDays: 8,
          unpaidDays: 1,
          quotaYearSplits: [],
          counters: NO_COUNTERS,
        },
      }));
      await goToStep2ForVacation();

      const futureDate = '2099-12-20';
      fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
      fireEvent.blur(screen.getByLabelText(/วันที่เริ่ม/));
      fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบระบบ' } });
      fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
      await screen.findByText(/ขั้นตอนที่ 3\/3/);

      const progressBar = await screen.findByRole('progressbar');
      expect(progressBar.parentElement.textContent).toMatch(/9\u00A0วัน \/ 8\u00A0วัน/);
      expect(screen.getByText(/เกินโควตาประจำปี/)).not.toBeNull();
      expect(progressBar.getAttribute('aria-valuenow')).toBe('100');
    });
  });
});

// §5 WARN_UNPAID_* gates: 10 of 17 LeaveRuleCode gates do not BLOCK; they submit normally and carry
// `ruleWarnings`/`unpaidByRuleDays` instead (see LeaveRuleEnforcement.java's class Javadoc).
// `messageTh` below is copied VERBATIM from LeaveRuleMessages' real ADVANCE_NOTICE/
// PROBATION_NOT_PASSED templates (WARN_UNPAID_TAIL included): never hand-translate backend copy.
const advanceNoticeWarning = {
  code: 'ADVANCE_NOTICE',
  params: {
    leaveTypeNameTh: 'ลาพักร้อน', noticeDays: '3', section: '5', days: '1',
  },
  messageTh: 'การลาพักร้อนต้องยื่นล่วงหน้าอย่างน้อย 3 วัน (ระเบียบข้อ 5) คำขอนี้ยื่นไม่ทันกำหนด '
    + 'แต่ยังส่งให้หัวหน้างานพิจารณาได้ หากอนุมัติ วันลา 1 วันจะไม่ได้รับค่าจ้าง',
};

function warningPreview({ ruleWarnings, unpaidByRuleDays, depth } = {}) {
  return {
    preview: {
      blocking: null,
      datesEvaluated: true,
      coverageEvaluated: depth === 'FULL',
      totalDays: 1,
      paidDays: 1 - unpaidByRuleDays,
      unpaidDays: unpaidByRuleDays,
      quotaYearSplits: [{
        quotaYear: 2099,
        totalDays: 1,
        paidDays: 1 - unpaidByRuleDays,
        unpaidDays: unpaidByRuleDays,
        quotaRemainingBefore: 6,
        quotaRemainingAfter: 5,
      }],
      ruleWarnings,
      unpaidByRuleDays,
      counters: NO_COUNTERS,
    },
  };
}

async function advanceToStep3WithFutureDate() {
  await goToStep2ForVacation();
  const futureDate = '2099-12-31';
  fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
  fireEvent.blur(screen.getByLabelText(/วันที่เริ่ม/));
  fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบ' } });
  fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
  await screen.findByText(/ขั้นตอนที่ 3\/3/);
}

describe('LeaveRequestPage: §5 WARN_UNPAID_* gates (V164 follow-up, 2026-09-09)', () => {
  beforeEach(() => {
    installDefaultLeaveMocks();
  });

  it('step 3: a warning renders (amber, non-danger) and submit stays enabled with the unpaid-copy label', async () => {
    api.leave.preview.mockImplementation((payload) => {
      if (!payload?.startDate) return Promise.resolve(dateless_ok_preview);
      return Promise.resolve(warningPreview({
        ruleWarnings: [advanceNoticeWarning], unpaidByRuleDays: 1, depth: payload.depth,
      }));
    });

    await advanceToStep3WithFutureDate();

    expect(await screen.findByText(/ต้องยื่นล่วงหน้าอย่างน้อย 3 วัน/)).not.toBeNull();
    // Not rendered inside role="alert" (that's step3Blocking/step3Error's own terminal wrapper) -- a warning does
    // not stop submission, so it must not be announced the same way.
    expect(screen.queryByRole('alert')).toBeNull();

    const submitButton = await screen.findByRole('button', { name: /ส่งคำขอ/ });
    await waitFor(() => expect(submitButton.disabled).toBe(false));
    expect(submitButton.textContent).toMatch(/ส่งคำขอ \(ไม่รับค่าจ้าง\)/);

    fireEvent.click(submitButton);
    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
  });

  it('step 3: a BLOCK still disables submit and shows only the danger panel, even alongside warnings', async () => {
    // Defensive: LeavePreviewDto's own contract says a non-null `blocking` always pairs with an EMPTY ruleWarnings
    // (a BLOCK wins outright) -- this proves the FRONTEND enforces that dominance itself rather than trusting a
    // well-behaved backend, in case a future response ever sends both.
    const blockingOutcome = {
      code: 'DEPARTMENT_COVERAGE',
      params: { uncoveredDate: '2099-12-31' },
      messageTh: 'ไม่มีพนักงานคนอื่นในแผนกทำงานในวันที่เลือก',
    };
    api.leave.preview.mockImplementation((payload) => {
      if (!payload?.startDate) return Promise.resolve(dateless_ok_preview);
      if (payload.depth === 'FULL') {
        return Promise.resolve({
          preview: {
            blocking: blockingOutcome,
            datesEvaluated: true,
            coverageEvaluated: true,
            totalDays: 1,
            paidDays: null,
            unpaidDays: null,
            quotaYearSplits: [],
            ruleWarnings: [advanceNoticeWarning],
            unpaidByRuleDays: 0,
            counters: NO_COUNTERS,
          },
        });
      }
      return Promise.resolve(approvedPreview(payload));
    });

    await advanceToStep3WithFutureDate();

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/ไม่มีพนักงานคนอื่นในแผนกทำงานในวันที่เลือก/);
    expect(screen.queryByText(/ต้องยื่นล่วงหน้าอย่างน้อย 3 วัน/)).toBeNull();
    const submitButton = screen.getByRole('button', { name: /ส่งคำขอ/ });
    expect(submitButton.disabled).toBe(true);
    expect(submitButton.textContent).not.toMatch(/ไม่รับค่าจ้าง/);
  });
});

