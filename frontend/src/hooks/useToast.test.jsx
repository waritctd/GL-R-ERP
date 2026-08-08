import React, { useCallback, useEffect } from 'react';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, render, renderHook, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useToast } from './useToast.js';
import { Toast } from '../components/common/Toast.jsx';

globalThis.React = React;

const FAILURE_MESSAGE = 'โหลดข้อมูลไม่สำเร็จ (regression fixture)';

// Mirrors App.jsx's real wiring (`const { toast, showToast, dismissToast } = useToast();`,
// threaded as a prop everywhere): this owns the ACTUAL hook, so `showToast`/`dismissToast` behave
// exactly as every real page receives them -- unlike a test's `vi.fn()` (used at ~100 call sites),
// which never changes identity and so cannot reproduce the infinite-render-loop hazard this
// harness exists to catch. Reused, not reinvented, from AttendancePage.test.jsx:39-58 /
// PayrollPage.test.jsx:27-39 (issue #422 adversarial review, BLOCKING 1) -- see either file for
// the original -- with one correction: `wrappedShowToast` here is wrapped in useCallback, keyed
// on [showToast, onShowToast]. Both existing copies leave it a plain per-render closure, which is
// harmless THERE only because AttendancePage/PayrollPage never put `showToast` directly in an
// effect's deps -- they still route through their own showToastRef (see those two files' own
// comments for why that ref is being kept deliberately rather than removed). THIS test's whole
// point is to put the real showToast directly in an effect's deps, the same as the other 16 of 18
// real call sites do, so the counting wrapper must not introduce its own instability on top of
// what's under test -- an unmemoized wrapper here would fail this test regardless of whether
// useToast.js is fixed, for a reason that has nothing to do with the bug being guarded against.
function AppShapedHarness({ children, onShowToast }) {
  const { toast, showToast, dismissToast } = useToast();
  const wrappedShowToast = useCallback((kind, message) => {
    onShowToast?.(kind, message);
    showToast(kind, message);
  }, [showToast, onShowToast]);
  return (
    <>
      {children(wrappedShowToast)}
      <Toast toast={toast} onDismiss={dismissToast} />
    </>
  );
}

// The exact effect shape 19 of the 21 total showToast-driven effect sites use directly, after
// this fix: the 18 sites / 10 files named in the task brief (dashboard/DivisionManagerOverview.jsx,
// ceoSettings/CeoSettingsPage.jsx, specialmoney/SpecialMoneyPanel.jsx, overtime/OvertimePanel.jsx,
// tickets/TicketDetailPage.jsx & TicketListPage.jsx, dashboard/AccountOverview.jsx &
// ManagerOverview.jsx & TicketDashboard.jsx, finance/AccountFinancePage.jsx), plus
// deposits/DepositNoticePage.jsx (1 more site -- its eslint-disabled workaround is also removed by
// this fix): a failed query's error surfaced via a toast fired from a `[query.error, showToast]`-
// keyed effect. (The other 2 of 21 -- attendance/AttendancePage.jsx, payroll/PayrollPage.jsx --
// keep their own showToastRef indirection; see those files' own comments for why.)
function FailingQueryToastConsumer({ showToast }) {
  const query = useQuery({
    queryKey: ['useToast-regression', 'failing-query'],
    queryFn: () => Promise.reject(new Error(FAILURE_MESSAGE)),
    retry: false,
  });
  useEffect(() => {
    if (query.error) showToast('error', query.error.message);
  }, [query.error, showToast]);
  return <div>content</div>;
}

describe('useToast — showToast/dismissToast referential stability', () => {
  it('showToast keeps the same identity across re-renders and after being called', () => {
    const { result, rerender } = renderHook(() => useToast());
    const first = result.current.showToast;

    rerender();
    expect(result.current.showToast).toBe(first);

    act(() => {
      result.current.showToast('info', 'hello');
    });
    rerender();
    expect(result.current.showToast).toBe(first);
  });

  it('dismissToast keeps the same identity across re-renders', () => {
    const { result, rerender } = renderHook(() => useToast());
    const first = result.current.dismissToast;

    rerender();
    expect(result.current.dismissToast).toBe(first);
  });
});

describe('useToast — App-shaped integration (P0 regression)', () => {
  // The regression test for the P0 defect itself. Before this fix, useToast() handed back a PLAIN
  // `showToast` function -- a new identity on every render of whatever owns useToast() (App.jsx).
  // Any effect shaped `}, [query.error, showToast])` self-sustained: showToast() -> setToast() ->
  // owner re-renders -> new showToast identity -> deps changed -> effect refires -> showToast()
  // again. A failed query settles into a PERMANENTLY truthy `.error` (production default is
  // `retry: 1`; this test uses `retry: false` for speed -- see FailingQueryToastConsumer -- the
  // retry count is not what drives the loop, showToast's identity churn is, and that churn is
  // identical regardless of how many retries preceded settlement), so nothing broke the cycle:
  // React threw "Maximum update depth exceeded" and the nearest ErrorBoundary (AppShell's, in
  // production) replaced the page.
  //
  // `onShowToast` throws once it is called more than once -- a trip wire, not a passive counter,
  // matching the pattern already proven in AttendancePage.test.jsx/PayrollPage.test.jsx: an
  // earlier version of this test instead waited an extra real tick before checking a plain call
  // count, and that extra real wall-clock time let the pre-fix cascade run long enough to hang the
  // test process (confirmed firsthand: it burned CPU for minutes, never throwing, never settling,
  // exactly as those two files' own comments warn) instead of failing it fast. Throwing from
  // inside the SAME synchronous callback the cascade is calling aborts it immediately.
  //
  // Mutation-checked: reverting useToast.js's useCallback([]) wrapper on showToast (restoring the
  // plain-function version) turns this test red -- see this branch's PR body for the transcript.
  // Restored byte-identical afterward.
  it('does not enter an infinite render loop when a query settles into a permanent error', async () => {
    const showToastCalls = [];
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={queryClient}>
        <AppShapedHarness
          onShowToast={(kind, message) => {
            showToastCalls.push({ kind, message });
            if (showToastCalls.length > 1) {
              throw new Error(`showToast called ${showToastCalls.length} times -- infinite render loop`);
            }
          }}
        >
          {(showToast) => <FailingQueryToastConsumer showToast={showToast} />}
        </AppShapedHarness>
      </QueryClientProvider>,
    );

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(FAILURE_MESSAGE);
    expect(showToastCalls).toEqual([{ kind: 'error', message: FAILURE_MESSAGE }]);
  });
});
