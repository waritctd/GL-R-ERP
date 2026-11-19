import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useIsMobile } from './useIsMobile.js';

function mockMatchMedia(matches) {
  const listeners = new Set();
  const mql = {
    matches,
    media: '(max-width: 720px)',
    addEventListener: (event, handler) => {
      if (event === 'change') listeners.add(handler);
    },
    removeEventListener: (event, handler) => {
      if (event === 'change') listeners.delete(handler);
    },
  };
  window.matchMedia = vi.fn().mockReturnValue(mql);
  return {
    mql,
    fire(nextMatches) {
      mql.matches = nextMatches;
      listeners.forEach((handler) => handler({ matches: nextMatches }));
    },
  };
}

describe('useIsMobile', () => {
  afterEach(() => {
    delete window.matchMedia;
  });

  it('reacts to viewport changes after mount', () => {
    const { fire } = mockMatchMedia(false);
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);

    act(() => {
      fire(true);
    });

    expect(result.current).toBe(true);
  });
});
