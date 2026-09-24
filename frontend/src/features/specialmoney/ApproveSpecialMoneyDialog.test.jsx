import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApproveSpecialMoneyDialog } from './ApproveSpecialMoneyDialog.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// This project does not wire up jest-dom's matchers (see LoginPage.test.jsx's own note) -- plain
// DOM assertions (.disabled, queryByText returning null, etc.) throughout, not toBeInTheDocument/
// toBeDisabled.
vi.mock('../../api/index.js', () => ({
  api: {
    specialMoney: {
      approvalPreview: vi.fn(),
    },
  },
}));

function renderDialog(props = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const defaultProps = {
    open: true,
    request: { id: 1, requestedAmount: 5000, employeeName: 'พนักงานทดสอบ', employeeCode: 'E001' },
    typeLabel: 'ชุดพนักงานใหม่',
    onConfirm: vi.fn().mockResolvedValue(undefined),
    onCancel: vi.fn(),
  };
  const merged = { ...defaultProps, ...props };
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApproveSpecialMoneyDialog {...merged} />
    </QueryClientProvider>,
  );
  return { ...view, props: merged, queryClient };
}

/** Waits for the underlying react-query cache entry to actually SETTLE (status 'success' or
 * 'error'), rather than merely for the mock fn to have been CALLED -- a promise resolving is a
 * microtask away from the mock recording the call, so "called" can be true a render before the
 * component has re-rendered with the result. Reviewed in (Opus, post-#1015-stack): asserting "no
 * ceiling UI" right after only `toHaveBeenCalled()` cannot distinguish "genuinely no ceiling" from
 * "the fetch simply hasn't resolved yet" -- a test that passes for the wrong reason and could never
 * catch a real regression. */
async function waitForPreviewSettled(queryClient, id) {
  await waitFor(() => {
    const state = queryClient.getQueryState(['specialMoney', 'approvalPreview', id]);
    expect(state?.status === 'success' || state?.status === 'error').toBe(true);
  });
}

/**
 * Coverage for the GET `/api/special-money/{id}/approval-preview` wiring: the ceiling line, the
 * over-ceiling warning + "ใช้ยอดเพดาน" shortcut, the client-side reason gate that mirrors
 * `SpecialMoneyService#ceoApproveFrom`'s own 400, the ฿0-ceiling wording/no-shortcut case, the
 * staleness/freshness gate (Opus review: never trust a cached or failed-refetch figure), and the
 * "no ceiling available" fallback (preview error, or the mock's deliberate `eligibleAmount: null`)
 * which must leave the dialog behaving exactly as #1015 shipped it.
 */
describe('ApproveSpecialMoneyDialog approval-ceiling preview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the policy-ceiling line once the preview resolves', async () => {
    api.specialMoney.approvalPreview.mockResolvedValue({
      preview: { requestedAmount: 5000, eligibleAmount: 1960, payrollMonth: '2026-08-01' },
    });
    renderDialog();

    expect(await screen.findByText('เพดานตามระเบียบสำหรับคำขอนี้ ฿1,960')).not.toBeNull();
  });

  it('shows the over-ceiling warning and a shortcut button that fills in the ceiling amount', async () => {
    api.specialMoney.approvalPreview.mockResolvedValue({
      preview: { requestedAmount: 5000, eligibleAmount: 1960, payrollMonth: '2026-08-01' },
    });
    renderDialog();

    await screen.findByText(/ยอดที่ขอเบิกเกินเพดาน/);
    const useCeilingButton = screen.getByRole('button', { name: 'ใช้ยอดเพดาน (฿1,960)' });

    const amountInput = document.getElementById('approve-special-money-amount');
    expect(amountInput.value).toBe('5000'); // pre-filled with the REQUESTED amount, untouched so far

    fireEvent.click(useCeilingButton);
    expect(amountInput.value).toBe('1960');
  });

  it('words the warning for an EXHAUSTED (฿0) ceiling and hides the shortcut button, while still requiring a reason', async () => {
    api.specialMoney.approvalPreview.mockResolvedValue({
      preview: { requestedAmount: 1500, eligibleAmount: 0, payrollMonth: '2026-08-01' },
    });
    renderDialog({ request: { id: 1, requestedAmount: 1500, employeeName: 'พนักงานทดสอบ' } });

    // The ceiling line still shows "฿0" -- zero is a REAL ceiling (an excluded per-diem province,
    // an exhausted MEDICAL balance), not "no data".
    expect(await screen.findByText('เพดานตามระเบียบสำหรับคำขอนี้ ฿0')).not.toBeNull();
    expect(screen.getByText('ไม่มีวงเงินคงเหลือตามระเบียบ — ต้องระบุเหตุผลหากต้องการอนุมัติ')).not.toBeNull();
    // Nothing to shortcut TO -- ฿0 is not a usable approved amount -- so no button is offered.
    expect(screen.queryByRole('button', { name: /ใช้ยอดเพดาน/ })).toBeNull();
    // The reason gate is still active: the pre-filled 1500 > the 0 ceiling, no reason yet.
    expect(screen.getByText('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามระเบียบ')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'อนุมัติ' }).disabled).toBe(true);
  });

  it('disables Confirm with a reason-required message when the entered amount exceeds the ceiling and no reason is given, and enables it once a reason is typed', async () => {
    api.specialMoney.approvalPreview.mockResolvedValue({
      preview: { requestedAmount: 5000, eligibleAmount: 1960, payrollMonth: '2026-08-01' },
    });
    renderDialog();

    await screen.findByText(/เพดานตามระเบียบสำหรับคำขอนี้/);
    // amountInput already reads 5000 (the pre-filled requested amount), which is over the 1960
    // ceiling -- so the reason-required gate should already be active with no reason typed.
    expect(screen.getByText('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามระเบียบ')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'อนุมัติ' }).disabled).toBe(true);

    fireEvent.change(document.getElementById('approve-special-money-reason'), {
      target: { value: 'อนุมัติเกินเพดานตามดุลยพินิจ CEO' },
    });

    expect(screen.queryByText('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามระเบียบ')).toBeNull();
    expect(screen.getByRole('button', { name: 'อนุมัติ' }).disabled).toBe(false);
  });

  it('allows Confirm with no reason when the entered amount equals the ceiling exactly, and sends that amount', async () => {
    api.specialMoney.approvalPreview.mockResolvedValue({
      preview: { requestedAmount: 5000, eligibleAmount: 1960, payrollMonth: '2026-08-01' },
    });
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    renderDialog({ onConfirm });

    await screen.findByText(/เพดานตามระเบียบสำหรับคำขอนี้/);
    fireEvent.change(document.getElementById('approve-special-money-amount'), { target: { value: '1960' } });

    expect(screen.queryByText('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามระเบียบ')).toBeNull();
    const confirmButton = screen.getByRole('button', { name: 'อนุมัติ' });
    expect(confirmButton.disabled).toBe(false);

    fireEvent.click(confirmButton);
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith(1960, null));
  });

  it('shows no ceiling UI and behaves exactly like before when the preview call fails', async () => {
    api.specialMoney.approvalPreview.mockRejectedValue(new Error('Not Found'));
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    const { queryClient } = renderDialog({ onConfirm });

    await waitForPreviewSettled(queryClient, 1);
    expect(screen.queryByText(/เพดานตามระเบียบสำหรับคำขอนี้/)).toBeNull();
    expect(screen.queryByText(/ยอดที่ขอเบิกเกินเพดาน/)).toBeNull();
    expect(screen.queryByText('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามระเบียบ')).toBeNull();

    // Confirm still works exactly as #1015 shipped it -- pre-filled requested amount, no reason.
    const confirmButton = screen.getByRole('button', { name: 'อนุมัติ' });
    expect(confirmButton.disabled).toBe(false);
    fireEvent.click(confirmButton);
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith(5000, null));
  });

  it('a refetch that FAILS while the dialog stays open (no close/reopen) does not resurrect the previous ceiling (M2)', async () => {
    // First fetch succeeds (฿3,000 renders); a SECOND fetch, triggered while this dialog stays
    // open the whole time, then fails. This is the one scenario the cache-clear-on-close effect
    // does NOT cover by itself -- react-query keeps the old successful `data` in place across a
    // failed refetch (confirmed against the installed @tanstack/query-core: after a rejected
    // refetch, `state.status` is `'error'` but `state.data` still holds the PREVIOUS success), so
    // only the `status === 'success'` gate (not "data is present") can catch this.
    api.specialMoney.approvalPreview
      .mockResolvedValueOnce({ preview: { requestedAmount: 5000, eligibleAmount: 3000, payrollMonth: '2026-08-01' } })
      .mockRejectedValueOnce(new Error('network blip'));
    const { queryClient } = renderDialog();

    await screen.findByText('เพดานตามระเบียบสำหรับคำขอนี้ ฿3,000');

    // Simulate `invalidateSpecialMoney()` firing from ELSEWHERE (another of the same employee's
    // requests just got approved) while THIS dialog stays open -- no close/reopen involved, so
    // the cache-clear-on-close effect never runs.
    queryClient.invalidateQueries({ queryKey: ['specialMoney', 'approvalPreview'] });

    // A single `waitFor` polling the RENDERED DOM (not just the query cache directly, which can
    // update a poll-cycle ahead of the component's own re-render): the old ฿3,000 must disappear,
    // not be resurrected by react-query's own stale `data` retention across the failed refetch.
    await waitFor(() => {
      const state = queryClient.getQueryState(['specialMoney', 'approvalPreview', 1]);
      expect(state?.status).toBe('error');
      expect(screen.queryByText(/เพดานตามระเบียบสำหรับคำขอนี้/)).toBeNull();
    });
  });

  it('shows no ceiling UI when the preview resolves with eligibleAmount: null (mock-mode contract)', async () => {
    api.specialMoney.approvalPreview.mockResolvedValue({
      preview: { requestedAmount: 5000, eligibleAmount: null, payrollMonth: null },
    });
    const { queryClient } = renderDialog();

    // Waits for the query to actually SETTLE (not just for the mock to have been "called") --
    // see waitForPreviewSettled's own comment for why the weaker check cannot tell "no ceiling
    // because eligibleAmount is null" apart from "no ceiling because the fetch hasn't resolved".
    await waitForPreviewSettled(queryClient, 1);
    expect(screen.queryByText(/เพดานตามระเบียบสำหรับคำขอนี้/)).toBeNull();
    expect(screen.queryByText(/ยอดที่ขอเบิกเกินเพดาน/)).toBeNull();
    expect(screen.getByRole('button', { name: 'อนุมัติ' }).disabled).toBe(false);
  });

  it('requests the preview for the request actually open, and refetches when a different request opens', async () => {
    api.specialMoney.approvalPreview.mockImplementation((id) => Promise.resolve({
      preview: { requestedAmount: 5000, eligibleAmount: id === 1 ? 1960 : 3000, payrollMonth: '2026-08-01' },
    }));

    const requestA = { id: 1, requestedAmount: 5000, employeeName: 'A' };
    const requestB = { id: 2, requestedAmount: 5000, employeeName: 'B' };
    const { rerender, queryClient, props } = renderDialog({ request: requestA });

    await screen.findByText('เพดานตามระเบียบสำหรับคำขอนี้ ฿1,960');
    expect(api.specialMoney.approvalPreview).toHaveBeenCalledWith(1);

    rerender(
      <QueryClientProvider client={queryClient}>
        <ApproveSpecialMoneyDialog {...props} request={requestB} />
      </QueryClientProvider>,
    );

    await screen.findByText('เพดานตามระเบียบสำหรับคำขอนี้ ฿3,000');
    expect(api.specialMoney.approvalPreview).toHaveBeenCalledWith(2);
  });

  it('makes no preview request at all while the dialog is closed', async () => {
    renderDialog({ open: false });

    // A brief tick to let any (incorrect) effect/query fire, then assert nothing did.
    await new Promise((resolve) => { setTimeout(resolve, 0); });
    expect(api.specialMoney.approvalPreview).not.toHaveBeenCalled();
  });

  it('never renders a STALE ceiling: reopening the dialog for the same request re-fetches and only shows the NEW figure, even momentarily', async () => {
    let resolveSecondFetch;
    api.specialMoney.approvalPreview
      .mockResolvedValueOnce({
        preview: { requestedAmount: 5000, eligibleAmount: 3000, payrollMonth: '2026-08-01' },
      })
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecondFetch = resolve; }));

    const request = { id: 1, requestedAmount: 5000, employeeName: 'พนักงานทดสอบ' };
    const { rerender, queryClient, props } = renderDialog({ request, open: true });

    // First opening: ceiling ฿3,000 renders.
    await screen.findByText('เพดานตามระเบียบสำหรับคำขอนี้ ฿3,000');

    // Close the dialog (simulates the CEO cancelling), then -- elsewhere -- another request from
    // the same employee gets approved, consuming most of the ฿3,000 cap. Reopen for the SAME
    // request: the second fetch is still pending (deliberately held open above).
    rerender(
      <QueryClientProvider client={queryClient}>
        <ApproveSpecialMoneyDialog {...props} request={request} open={false} />
      </QueryClientProvider>,
    );
    rerender(
      <QueryClientProvider client={queryClient}>
        <ApproveSpecialMoneyDialog {...props} request={request} open />
      </QueryClientProvider>,
    );

    // While the fresh fetch is still in flight, the OLD ฿3,000 figure must NOT render -- this is
    // the exact staleness bug the Opus review found (queryClient.js's global staleTime: 30_000
    // would otherwise let the cached value straight through).
    expect(screen.queryByText('เพดานตามระเบียบสำหรับคำขอนี้ ฿3,000')).toBeNull();
    expect(screen.queryByText(/เพดานตามระเบียบสำหรับคำขอนี้/)).toBeNull();

    // Resolve the second fetch with the NEW, lower ceiling -- only this figure ever appears now.
    resolveSecondFetch({ preview: { requestedAmount: 5000, eligibleAmount: 1000, payrollMonth: '2026-08-01' } });
    await screen.findByText('เพดานตามระเบียบสำหรับคำขอนี้ ฿1,000');
    expect(screen.queryByText('เพดานตามระเบียบสำหรับคำขอนี้ ฿3,000')).toBeNull();
  });
});
