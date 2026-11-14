import React, { useCallback, useEffect } from 'react';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, render, renderHook, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useToast } from './useToast.js';
import { Toast } from '../components/common/Toast.jsx';

globalThis.React = React;

const FAILURE_MESSAGE = 'โหลดข้อมูลไม่สำเร็จ (regression fixture)';

// Mirrors App.jsx's real wiring (`const { toast, showToast, dismissToast } = useToast;`, threaded as a prop
// everywhere): this owns the ACTUAL hook, so `showToast`/`dismissToast` behave exactly as every real page
// receives them.
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

// The exact effect shape 19 of the 21 total showToast-driven effect sites use directly, after this fix: the 18
// sites / 10 files named in the task brief, plus deposits/DepositNoticePage.jsx: a failed query's error
// surfaced via a toast fired from a `[query.error, showToast]`- keyed effect.
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
  // The regression test for the P0 defect itself. Before this fix, useToast() handed back a PLAIN `showToast`
  // function -- a new identity on every render of whatever owns useToast() (App.jsx). Throwing from inside the
  // SAME synchronous callback the cascade is calling aborts it immediately.
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
