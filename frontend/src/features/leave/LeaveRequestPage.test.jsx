import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
      // #leave-holiday-visibility: step 2 now reads this unconditionally (the วันหยุดที่จะถึง
      // panel AND the per-selection calendar-context note both call it) -- every test that
      // reaches step 2 needs a default resolved value, or both queries error out silently.
      calendarContext: vi.fn(),
    },
  },
}));

// browser-image-compression genuinely returns a plain Blob with no `.name` -- this mock
// reproduces that faithfully rather than a File, which is exactly the shape that exposed the
// "blob" filename bug in #498/#504. A mock that quietly upgrades the library's real return type
// would make this test pass whether or not the component re-wraps it.
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
  // Returns the render utils (container, etc.) so callers that need to reach past the rendered
  // output -- e.g. dispatching a raw DOM event straight at the <form> -- can do so without
  // re-implementing this same setup. Existing callers that only awaited this for its side effects
  // are unaffected; an ignored return value changes nothing for them.
  const utils = renderComposer();
  const vacationButton = await screen.findByRole('button', { name: /ลาพักร้อน/ });
  fireEvent.click(vacationButton);
  fireEvent.click(screen.getByRole('button', { name: 'ถัดไป' }));
  await screen.findByText(/ขั้นตอนที่ 2\/3/);
  return utils;
}

describe('LeaveRequestPage (Phase A2, #485)', () => {
  beforeEach(() => {
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
    // Default: no holidays anywhere -- keeps every test below on the empty-state วันหยุดที่จะถึง
    // panel and the "all working days" calendar-context note, uninvolved with what each test
    // actually asserts on. The holiday-visibility cases below override this per-test.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
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

    // Two buttons match /ลาอุปสมบท/: the "ประเภทอื่นๆ" disclosure toggle (its subtitle lists
    // "ลาคลอด ลาทหาร ลาอุปสมบท") and the actual type-choice button -- disambiguate by picking the
    // one that is itself the disabled type button, not the toggle.
    await waitFor(() => {
      const matches = screen.getAllByRole('button', { name: /ลาอุปสมบท/ });
      const ordinationButton = matches.find((button) => button.hasAttribute('disabled'));
      expect(ordinationButton).toBeTruthy();
    });
    expect(await screen.findByText(/ใช้สิทธิ์ได้เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน/)).not.toBeNull();
  });

  // Real-backend gap found via click-through testing (not visible under mocks -- LeaveService's
  // resolveTargetEmployee() gate has no mock-mode equivalent): a preview call can fail outright
  // with a plain 403 instead of returning a LeaveRuleOutcome, e.g. HR/CEO requesting leave for
  // themselves. That has no `code` for LeaveRulePanel's RULE_META, so before this fix it silently
  // read as "not blocking" (an errored query's `data` is undefined) and left the type looking
  // available with a permanently unresolved skeleton -- see PreviewErrorNotice's own comment.
  it('step 1: a preview call that fails outright (not a LeaveRuleOutcome) disables the type with the real error message', async () => {
    api.leave.preview.mockImplementation((payload) => {
      if (payload?.leaveTypeCode === 'PERSONAL') {
        return Promise.reject(new Error('ฝ่ายบุคคลและผู้บริหารไม่สามารถยื่นคำขอลาให้ตนเองได้ กรุณาให้ผู้อื่นดำเนินการแทน'));
      }
      return Promise.resolve(dateless_ok_preview);
    });

    renderComposer();

    await waitFor(() => {
      const button = screen.getByRole('button', { name: /ลากิจ/ });
      expect(button.disabled).toBe(true);
    });
    expect(await screen.findByText(/ฝ่ายบุคคลและผู้บริหารไม่สามารถยื่นคำขอลาให้ตนเองได้/)).not.toBeNull();
  });

  it('step 1 -> 2: blocks advancing until a type is chosen, and moves focus to the step-2 heading', async () => {
    renderComposer();
    const nextButton = await screen.findByRole('button', { name: 'ถัดไป' });
    expect(nextButton.disabled).toBe(true);

    fireEvent.click(await screen.findByRole('button', { name: /ลาพักร้อน/ }));
    await waitFor(() => expect(nextButton.disabled).toBe(false));
    fireEvent.click(nextButton);

    const heading = await screen.findByText(/ขั้นตอนที่ 2\/3/);
    await waitFor(() => expect(heading.closest('h2')).toBe(document.activeElement));
  });

  it('step 2: start date before today is rejected and cannot advance to step 3', async () => {
    await goToStep2ForVacation();

    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: '2020-01-01' } });
    expect(await screen.findByText('วันที่เริ่มลาต้องไม่ก่อนวันนี้')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    expect(screen.queryByText(/ขั้นตอนที่ 3\/3/)).toBeNull();
  });

  it('step 2: honestly surfaces coverageEvaluated=false under the debounced QUICK preview', async () => {
    await goToStep2ForVacation();
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: '2099-12-31' } });
    fireEvent.change(screen.getByLabelText(/วันที่สิ้นสุด/), { target: { value: '2099-12-31' } });

    expect(screen.getByText(/ยังไม่ตรวจภาระงานของแผนก/)).not.toBeNull();
    await waitFor(() => expect(api.leave.preview).toHaveBeenCalledWith(
      expect.objectContaining({ depth: 'QUICK' }),
      expect.anything(),
    ));
  });

  // #leave-holiday-visibility (PR 3): holidays are now visible BEFORE a date is picked, not only
  // after -- this panel sits above the date inputs and never touches startDate/endDate at all, so
  // rendering it here (with no date field ever changed by this test) is itself the proof it does
  // not depend on what the employee has, or has not yet, chosen.
  it('step 2: shows the วันหยุดที่จะถึง panel above the date inputs, so holidays are visible before picking a date', async () => {
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: {
        holidays: [{ holidayDate: '2026-08-15', nameTh: 'วันหยุดทดสอบ' }],
        // Empty on purpose: the reactive per-selection note further down only lists holiday
        // NAMES once nonWorkingDates is non-empty (see that block's own comment) -- keeping it
        // empty here means any "วันหยุดทดสอบ" on screen can only be coming from the panel.
        nonWorkingDates: [],
      },
    });

    await goToStep2ForVacation();

    const panelHeading = await screen.findByRole('heading', { name: 'วันหยุดที่จะถึง' });
    expect(panelHeading.closest('section').textContent).toMatch(/วันหยุดทดสอบ/);
  });

  // #leave-holiday-visibility (PR 3): real วันหยุดบริษัท names run up to 149 chars
  // (MOCK_HOLIDAY_DATES in mockApi.js is a verbatim copy of production's hr.holiday -- the reason
  // V129 widened name_th from VARCHAR(120) to TEXT). The reactive per-selection note used to
  // interpolate `${formatDate(...)} (${nameTh})` straight into one run-on sentence, comma-joined
  // with every other holiday in range -- fine at a hand-written fixture's 9-17 chars, an
  // unreadable wall of text at production length with two holidays in one selected range.
  it('step 2: a multi-day range with two long company-holiday names renders each on its own line, not a comma-joined wall of text', async () => {
    const LONG_NAME_1 = 'ชดเชยวันคล้ายวันพระบรมราชสมภพ พระบาทสมเด็จพระบรมชนกาธิเบศร มหาภูมิพลอดุลยเดชมหาราช บรมนาถบพิตร วันชาติ และวันพ่อแห่งชาติ (วันเสาร์ที่ 5 ธันวาคม 2569)';
    const LONG_NAME_2 = 'วันพระบาทสมเด็จพระพุทธยอดฟ้าจุฬาโลกมหาราช และวันที่ระลึกมหาจักรีบรมราชวงศ์';
    const start = '2099-12-07';
    const end = '2099-12-08';

    // Keyed on params so only the note's own SELECTED-range query (start..end) returns these
    // holidays -- the วันหยุดที่จะถึง panel's separate ~90-day-forward query stays empty, which
    // isolates this assertion to the note under test instead of also matching the panel's own
    // (legitimately duplicate, per OvertimePage.test.jsx's own precedent) render of the same
    // names.
    api.leave.calendarContext.mockImplementation((params) => Promise.resolve({
      calendarContext: params?.from === start && params?.to === end
        ? {
          holidays: [
            { holidayDate: start, nameTh: LONG_NAME_1 },
            { holidayDate: end, nameTh: LONG_NAME_2 },
          ],
          nonWorkingDates: [start, end],
        }
        : { holidays: [], nonWorkingDates: [] },
    }));

    await goToStep2ForVacation();
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: start } });
    fireEvent.change(screen.getByLabelText(/วันที่สิ้นสุด/), { target: { value: end } });

    await waitFor(() => {
      expect(screen.getByTestId('calendar-context-note').textContent).toMatch(LONG_NAME_1);
    });
    const note = screen.getByTestId('calendar-context-note');

    // Each long name is its OWN element (an exact-text match only succeeds if some element's
    // full text is exactly this string, not a substring of a longer run-on sentence) sitting in
    // its own list row -- never comma-joined into the same element as the other.
    const firstNameEl = within(note).getByText(LONG_NAME_1);
    const secondNameEl = within(note).getByText(LONG_NAME_2);
    expect(firstNameEl.closest('li')).not.toBeNull();
    expect(secondNameEl.closest('li')).not.toBeNull();
    expect(firstNameEl.closest('li')).not.toBe(secondNameEl.closest('li'));
    expect(firstNameEl.closest('li').textContent).not.toMatch(LONG_NAME_2);
    expect(secondNameEl.closest('li').textContent).not.toMatch(LONG_NAME_1);
  });

  it('full happy path: sends the same create() payload shape the pre-A2 form sent, via step 3', async () => {
    await goToStep2ForVacation();

    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
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
      attachmentFile: null,
    });
  });

  // Found in #498/#504 (same defect, different component): imageCompression() returns a Blob, and
  // FormData built from a bare Blob has no filename to send, so the multipart part's filename
  // defaults to the literal string "blob" per spec. Only JPG/PNG hit this -- PDFs skip
  // compression entirely, which is why the bug reads as image-only.
  it('re-wraps the compressed image so create() receives the original filename, not "blob"', async () => {
    await goToStep2ForVacation();

    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบระบบ' } });

    const original = new File(['fake-jpeg-bytes'], 'sick-note.jpg', { type: 'image/jpeg' });
    fireEvent.change(document.getElementById('leave-attachment-file'), { target: { files: [original] } });

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    await screen.findByText(/ขั้นตอนที่ 3\/3/);
    const submitButton = await screen.findByRole('button', { name: /ส่งคำขอ/ });
    await waitFor(() => expect(submitButton.disabled).toBe(false));
    fireEvent.click(submitButton);

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
    const { attachmentFile } = api.leave.create.mock.calls[0][0];

    // The regression this guards: without the File re-wrap, `attachmentFile.name` is undefined (a
    // bare Blob has no `.name`), and FormData/fetch would send "blob" to the real backend.
    expect(attachmentFile.name).toBe('sick-note.jpg');
    expect(attachmentFile).toBeInstanceOf(File);
    expect(attachmentFile.type).toBe('image/jpeg');
  });

  it('does not touch PDFs -- they skip compression and keep their name for a different reason', async () => {
    const imageCompression = (await import('browser-image-compression')).default;
    await goToStep2ForVacation();

    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบระบบ' } });

    const pdf = new File(['fake-pdf-bytes'], 'sick-note.pdf', { type: 'application/pdf' });
    fireEvent.change(document.getElementById('leave-attachment-file'), { target: { files: [pdf] } });

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    await screen.findByText(/ขั้นตอนที่ 3\/3/);
    const submitButton = await screen.findByRole('button', { name: /ส่งคำขอ/ });
    await waitFor(() => expect(submitButton.disabled).toBe(false));
    fireEvent.click(submitButton);

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
    const { attachmentFile } = api.leave.create.mock.calls[0][0];

    expect(imageCompression).not.toHaveBeenCalled();
    expect(attachmentFile.name).toBe('sick-note.pdf');
  });

  it('toggling sub-day leave forces endDate to startDate and sends the chosen times', async () => {
    await goToStep2ForVacation();

    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'หาหมอครึ่งวัน' } });
    fireEvent.click(screen.getByLabelText(/ลาบางส่วนของวัน/));

    expect(screen.getByLabelText(/วันที่สิ้นสุด/).value).toBe(futureDate);
    fireEvent.change(screen.getByLabelText(/เวลาเริ่ม/), { target: { value: '08:30' } });
    fireEvent.change(screen.getByLabelText(/เวลาสิ้นสุด/), { target: { value: '12:30' } });

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    await screen.findByText(/ขั้นตอนที่ 3\/3/);
    const submitButton = await screen.findByRole('button', { name: /ส่งคำขอ/ });
    await waitFor(() => expect(submitButton.disabled).toBe(false));
    fireEvent.click(submitButton);

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
    const payload = api.leave.create.mock.calls[0][0];
    expect(payload.startDate).toBe(futureDate);
    expect(payload.endDate).toBe(futureDate);
    expect(payload.startTime).toBe('08:30');
    expect(payload.endTime).toBe('12:30');
  });

  it('deep link (?type=&start=&end=) lands directly on step 2 with the type/dates prefilled', async () => {
    renderComposer(['/leave/new?type=SICK&start=2099-06-01&end=2099-06-02']);
    await screen.findByText(/ขั้นตอนที่ 2\/3/);
    expect(screen.getByLabelText(/วันที่เริ่ม/).value).toBe('2099-06-01');
    expect(screen.getByLabelText(/วันที่สิ้นสุด/).value).toBe('2099-06-02');
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
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/ไม่มีพนักงานคนอื่นในแผนกทำงานในวันที่เลือก/);
    expect(screen.getByRole('button', { name: /ส่งคำขอ/ }).disabled).toBe(true);
  });

  // The safety-relevant half of the same gap as the step-1 test above: before this fix,
  // `step3Blocking` read null on an errored FULL preview (undefined `data`), so the submit button
  // fell back to enabled with no explanation -- an errored authorization check must gate submit
  // exactly like a real blocking verdict, not silently read as a clean pass.
  it('step 3: a preview call that fails outright (not a LeaveRuleOutcome) renders role="alert" and disables submit', async () => {
    api.leave.preview.mockImplementation((payload) => {
      if (!payload?.startDate) return Promise.resolve(dateless_ok_preview);
      if (payload.depth === 'FULL') {
        return Promise.reject(new Error('ฝ่ายบุคคลและผู้บริหารไม่สามารถยื่นคำขอลาให้ตนเองได้ กรุณาให้ผู้อื่นดำเนินการแทน'));
      }
      return Promise.resolve(approvedPreview(payload));
    });

    await goToStep2ForVacation();
    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/ฝ่ายบุคคลและผู้บริหารไม่สามารถยื่นคำขอลาให้ตนเองได้/);
    expect(screen.getByRole('button', { name: /ส่งคำขอ/ }).disabled).toBe(true);
  });

  it('cancel from step 1 navigates back to /leave with the returnTab carried through', async () => {
    renderComposer(['/leave/new?returnTab=review']);
    fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิก' }));
    await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/leave?tab=review'));
  });
});

// HTML implicit submission (fix/form-enter-submits-real-records): a <form> with no submit button
// but exactly ONE field that blocks implicit submission fires a real 'submit' event on Enter, no
// button ever pressed. jsdom does not implement that algorithm at all (pressing "Enter" via
// fireEvent does nothing special) -- e2e/implicit-submission.spec.js is the layer that reproduces
// the real thing. See `canSubmit`'s own comment in LeaveRequestPage.jsx for why this describe
// block is hardening, not a fix for a currently-reachable bug.
//
// `submitWithSubmitter` below, not `fireEvent.submit(form)`: this form is now `<SafeForm
// canSubmit={step === 3} ...>` (#safe-form-primitive), and `canSubmit` is a RESTRICTION layered on
// top of SafeForm's own submitter guard, not a replacement for it -- both checks always apply.
// `fireEvent.submit(form)` dispatches a plain synthetic Event with no `.submitter` property at all
// under jsdom, so it would be blocked by the submitter guard on EVERY step including step 3, which
// would make "does NOT call create()" pass for the wrong reason on steps 1/2 (no submitter present
// at all, not because `canSubmit` rejected it -- this repo's own documented vacuous-test shape)
// and make "calls create()" on step 3 fail outright. A manually constructed `SubmitEvent` with an
// explicit `submitter` is picked up by React's onSubmit exactly like a real click (verified), so
// every case below now exercises `canSubmit` specifically. Mirrors
// TaxAllowanceForm.test.jsx's "form-level submit gate blocks Enter-key implicit submission" and
// TicketCreateModal.test.jsx's `submitForm()`.
function submitWithSubmitter(form) {
  form.dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true, submitter: document.createElement('button') }));
}

describe('LeaveRequestPage implicit submission (Enter-key) hardening', () => {
  // The gate's blocking branch (`event.preventDefault(); return;`) is synchronous, so a correctly
  // gated step 1/2 never even calls `handleSubmit`, let alone awaits anything — but an assertion
  // that only checks SYNCHRONOUSLY, right after the submit dispatch, proves nothing about whether
  // the gate is doing that work: `handleSubmit(onSubmit)` is asynchronous (`onSubmit` itself is
  // `async`), so if the gate were ever removed, `api.leave.create` would still not have been
  // called yet at that exact synchronous instant either -- the assertion would keep passing for
  // the wrong reason. Found via this file's own mutation-check (temporarily making
  // `handleFormSubmit` call `handleSubmit(onSubmit)` unconditionally): a bare synchronous
  // `expect(...).not.toHaveBeenCalled()` here stayed green regardless. Waiting a beat first means
  // "not called" is checked once an unguarded submit would already have gotten there.
  async function flushAsyncSubmitChain() {
    await new Promise((resolve) => { setTimeout(resolve, 50); });
  }

  it('a real submit event on step 1 does not call create()', async () => {
    const { container } = renderComposer();
    await screen.findByRole('button', { name: /ลาพักร้อน/ });

    submitWithSubmitter(container.querySelector('form'));
    await flushAsyncSubmitChain();

    expect(api.leave.create).not.toHaveBeenCalled();
  });

  it('a real submit event on step 2 does not call create()', async () => {
    const { container } = await goToStep2ForVacation();
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: '2099-12-31' } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบระบบ' } });

    submitWithSubmitter(container.querySelector('form'));
    await flushAsyncSubmitChain();

    expect(api.leave.create).not.toHaveBeenCalled();
  });

  it('a real submit event on step 3 calls create()', async () => {
    const { container } = await goToStep2ForVacation();
    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    await screen.findByText(/ขั้นตอนที่ 3\/3/);
    await waitFor(() => expect(screen.getByRole('button', { name: /ส่งคำขอ/ }).disabled).toBe(false));

    submitWithSubmitter(container.querySelector('form'));

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
  });
});
