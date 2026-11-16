import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PeopleInsightsPage } from './PeopleInsightsPage.jsx';
import { PEOPLE_FIXTURE } from './peopleInsightsFixture.js';
import { api } from '../../api/index.js';
import { formatThaiDate } from '../../utils/format.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({ api: { peopleInsights: { get: vi.fn() } } }));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><PeopleInsightsPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

const serve = (overrides = {}) => api.peopleInsights.get.mockResolvedValue({ ...PEOPLE_FIXTURE, ...overrides });
const fig = (id) => screen.getByTestId(id);

// Braces matter: a returned mock would be run by vitest as the teardown.
beforeEach(() => { api.peopleInsights.get.mockReset(); });
afterEach(cleanup);

describe('PeopleInsightsPage', () => {
  it('shows the latest month figures, the as-of date and no prototype-only sentence', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    expect(within(fig('fig-leave')).getByText('14 วัน')).toBeTruthy();
    expect(fig('fig-leave').textContent).toContain('มี.ค. 2 วัน');
    expect(within(fig('fig-late')).getByText('1 ครั้ง')).toBeTruthy();
    expect(screen.getByText(new RegExp(`ข้อมูล ณ ${formatThaiDate('2026-04-10')}`))).toBeTruthy();
    expect(screen.queryByText(/ตัวอย่างนี้/)).toBeNull();
  });

  it('updates the figures when a month bar is clicked', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    fireEvent.click(screen.getByRole('button', { name: /^มี\.ค\./ }));
    expect(within(fig('fig-leave')).getByText('2 วัน')).toBeTruthy();
    expect(within(fig('fig-late')).getByText('2 ครั้ง')).toBeTruthy();
  });

  it('notes the time-exempt person in the check-in table and dashes their yearly late count', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    const checkIn = screen.getByTestId('checkin-table');
    expect(within(checkIn).getByText('ไม่นับเวลาเข้างาน')).toBeTruthy();
    expect(checkIn.closest('section').textContent).toContain('พนักงานที่ได้รับยกเว้น (เช่น พนักงานขายนอกสถานที่) ไม่นับสายและไม่นับการสแกนออก');
    const yearly = screen.getByTestId('quota-table');
    const exemptRow = within(yearly).getByText('ซี').closest('tr');
    expect(exemptRow.querySelector('td:last-child').textContent).toBe('—');
  });

  it('hides the scope switcher when there is one scope, and shows only the scope label as subtitle', async () => {
    serve({ scopes: [{ id: '7', label: 'ทีมทดสอบ' }], scope: { id: '7', label: 'ทีมทดสอบ' }, teams: [] });
    const { container } = renderPage();
    await screen.findByTestId('people-insights');
    expect(screen.queryByRole('tablist')).toBeNull();
    expect(within(container.querySelector('header')).getByText('ทีมทดสอบ')).toBeTruthy();
    expect(container.querySelector('header').textContent).not.toMatch(/\d+ คน/); // no headcount in the header
  });

  it('switches scope with several scopes, refetching with the new scope', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    expect(api.peopleInsights.get).toHaveBeenLastCalledWith({});
    serve({ scope: { id: '7', label: 'ทีมทดสอบ' }, teams: [] });
    fireEvent.click(within(screen.getByRole('tablist')).getByRole('tab', { name: 'ทีมทดสอบ' }));
    await waitFor(() => expect(api.peopleInsights.get).toHaveBeenLastCalledWith({ scope: '7' }));
    await waitFor(() => expect(screen.queryByText('เทียบตามสายงาน')).toBeNull());
  });

  it('shows the team comparison only when the response has teams', async () => {
    serve();
    const { unmount } = renderPage();
    expect(await screen.findByText('เทียบตามสายงาน')).toBeTruthy();
    unmount();
    serve({ teams: [] });
    renderPage();
    await screen.findByTestId('people-insights');
    expect(screen.queryByText('เทียบตามสายงาน')).toBeNull();
  });

  it('takes the leave quotas from leaveTypes', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    const yearly = screen.getByTestId('quota-table');
    expect(within(yearly).getByText('ลาป่วย (จาก 10)')).toBeTruthy();
    expect(within(yearly).getByText('ลากิจ (จาก 4)')).toBeTruthy();
    // 13 sick days against a quota of 10 is flagged; 2 against it is not.
    expect(within(yearly).getByText('13').className).toContain('status-danger');
  });

  it('flags over-quota from quotaUsage, not from the leave rows', async () => {
    // บี took 1 personal day by start date, but 5 were charged to this quota year (4 allowed); เอ's 13 sick days by start date are 3 by quota year.
    serve({ quotaUsage: [
      { employeeId: 1, leaveTypeCode: 'SICK', days: 3 },
      { employeeId: 2, leaveTypeCode: 'PERSONAL', days: 5 },
    ] });
    renderPage();
    await screen.findByTestId('people-insights');
    const yearly = screen.getByTestId('quota-table');
    expect(within(yearly).getByText('5').className).toContain('status-danger');
    expect(within(yearly).queryByText('13')).toBeNull();
    expect(screen.getByText('บี ลากิจเกินสิทธิ์')).toBeTruthy();
    expect(screen.queryByText('เอ ลาป่วยเกินสิทธิ์')).toBeNull();
  });

  it('says why the late-filed finding is what it is: no sick leave, no back-dated entries', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    expect(screen.getByText('ไม่นับลาป่วย และรายการที่ฝ่ายบุคคลบันทึกย้อนหลัง')).toBeTruthy();
  });

  it('says so when a month has no leave', async () => {
    serve({ leave: [], attendance: [], people: PEOPLE_FIXTURE.people.filter((p) => !p.timeExempt) });
    renderPage();
    expect(await screen.findByText('ไม่มีการลาในเดือนนี้')).toBeTruthy();
    expect(screen.getByText('ไม่มีข้อมูลสแกนในเดือนนี้')).toBeTruthy();
  });

  it('shows a skeleton while loading', () => {
    api.peopleInsights.get.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId('people-insights-loading')).toBeTruthy();
  });

  it('shows an error state when the request fails', async () => {
    api.peopleInsights.get.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    renderPage();
    expect((await screen.findByRole('alert')).textContent).toContain('โหลดข้อมูลไม่สำเร็จ');
  });

  it('first-load failure shows the bare error and no report', async () => {
    api.peopleInsights.get.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('โหลดข้อมูลไม่สำเร็จ');
    expect(screen.queryByTestId('people-insights')).toBeNull();
  });

  it('a failed scope switch keeps the previous report, shows the alert, and snaps the selection back', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    api.peopleInsights.get.mockImplementation(async (params = {}) => {
      if (params.scope === '7') throw Object.assign(new Error('boom'), { status: 500 });
      return PEOPLE_FIXTURE;
    });
    fireEvent.click(within(screen.getByRole('tablist')).getByRole('tab', { name: 'ทีมทดสอบ' }));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('โหลดข้อมูลไม่สำเร็จ · กำลังแสดงข้อมูลที่โหลดได้ล่าสุด');
    expect(screen.getByTestId('people-insights')).toBeTruthy(); // the last good report is still there
    expect(within(fig('fig-leave')).getByText('14 วัน')).toBeTruthy();
    await waitFor(() => expect(within(screen.getByRole('tablist')).getByRole('tab', { name: 'ทีมทดสอบ' }).getAttribute('aria-selected')).toBe('false'));
    expect(within(screen.getByRole('tablist')).getByRole('tab', { name: PEOPLE_FIXTURE.scope.label }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('alert')).toBeTruthy(); // refetching the snapped-back selection does not clear it
  });

  it('the failure alert clears after a later successful load', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    let fail = true;
    api.peopleInsights.get.mockImplementation(async (params = {}) => {
      if (params.scope === '7') {
        if (fail) throw Object.assign(new Error('boom'), { status: 500 });
        return { ...PEOPLE_FIXTURE, scope: { id: '7', label: 'ทีมทดสอบ' }, teams: [] };
      }
      return PEOPLE_FIXTURE;
    });
    fireEvent.click(within(screen.getByRole('tablist')).getByRole('tab', { name: 'ทีมทดสอบ' }));
    await screen.findByRole('alert');
    fail = false;
    fireEvent.click(await within(screen.getByRole('tablist')).findByRole('tab', { name: 'ทีมทดสอบ' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(document.querySelector('header').textContent).toContain('ทีมทดสอบ');
    expect(screen.queryByText('เทียบตามสายงาน')).toBeNull(); // the new scope's data, not the old report
  });

  it('a 403 on a scope switch still shows the no-access state', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    api.peopleInsights.get.mockRejectedValue(Object.assign(new Error('forbidden'), { status: 403 }));
    fireEvent.click(within(screen.getByRole('tablist')).getByRole('tab', { name: 'ทีมทดสอบ' }));
    expect(await screen.findByTestId('people-insights-no-access')).toBeTruthy();
  });

  it('shows a no-access state on 403', async () => {
    api.peopleInsights.get.mockRejectedValue(Object.assign(new Error('forbidden'), { status: 403 }));
    renderPage();
    expect(await screen.findByTestId('people-insights-no-access')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('marks a person who left with a muted tag after their name', async () => {
    serve({ people: PEOPLE_FIXTURE.people.map((p) => (p.employeeId === 2 ? { ...p, active: false } : p)) });
    renderPage();
    await screen.findByTestId('people-insights');
    const row = within(screen.getByTestId('quota-table')).getByText('บี').closest('td');
    expect(row.textContent).toContain('พ้นสภาพ');
    expect(within(screen.getByTestId('quota-table')).getByText('เอ').closest('td').textContent).not.toContain('พ้นสภาพ');
  });

  it('steps the year with the arrows, disabled at the ends of years, and refetches with the chosen year', async () => {
    api.peopleInsights.get.mockImplementation(async (params = {}) => (params.year === 2025
      ? { ...PEOPLE_FIXTURE, year: 2025, leave: [], attendance: [], quotaUsage: [] }
      : PEOPLE_FIXTURE));
    renderPage();
    await screen.findByTestId('people-insights');
    const next = screen.getByRole('button', { name: 'ปีถัดไป' });
    const prev = screen.getByRole('button', { name: 'ปีก่อนหน้า' });
    expect(next.disabled).toBe(true); // 2026 is the last year
    expect(prev.disabled).toBe(false);
    fireEvent.click(prev);
    await waitFor(() => expect(api.peopleInsights.get).toHaveBeenLastCalledWith({ year: 2025 }));
    await waitFor(() => expect(screen.getByText(/วันลารายเดือน ปี 2568/)).toBeTruthy());
    expect(within(fig('fig-leave')).getByText('0 วัน')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'ปีก่อนหน้า' }).disabled).toBe(true); // 2025 is the first
    expect(screen.getByRole('button', { name: 'ปีถัดไป' }).disabled).toBe(false);
  });

  it('does not present the old figures as the new selection while a scope switch loads', async () => {
    serve();
    renderPage();
    await screen.findByTestId('people-insights');
    api.peopleInsights.get.mockReturnValue(new Promise(() => {}));
    fireEvent.click(within(screen.getByRole('tablist')).getByRole('tab', { name: 'ทีมทดสอบ' }));
    await waitFor(() => expect(screen.getByTestId('people-insights').getAttribute('aria-busy')).toBe('true'));
    expect(screen.getByTestId('people-insights').className).toContain('opacity-50');
    expect(document.querySelector('header').textContent).toContain('ทีมทดสอบ');
  });
});
