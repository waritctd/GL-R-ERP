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

  it('shows a no-access state on 403', async () => {
    api.peopleInsights.get.mockRejectedValue(Object.assign(new Error('forbidden'), { status: 403 }));
    renderPage();
    expect(await screen.findByTestId('people-insights-no-access')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
