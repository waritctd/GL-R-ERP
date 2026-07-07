import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ErrorBoundary } from './ErrorBoundary.jsx';

globalThis.React = React;

function Bomb() {
  throw new Error('boom');
}

function Healthy() {
  return <span>all good</span>;
}

describe('ErrorBoundary', () => {
  describe('when a child throws', () => {
    let consoleErrorSpy;

    beforeEach(() => {
      consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    });

    afterEach(() => {
      consoleErrorSpy.mockRestore();
    });

    it('renders the default fallback instead of propagating the error', () => {
      expect(() => {
        render(
          <ErrorBoundary>
            <Bomb />
          </ErrorBoundary>,
        );
      }).not.toThrow();

      expect(screen.getByText('เกิดข้อผิดพลาด / Something went wrong')).toBeTruthy();
    });

    it('recovers a now-healthy child after "Try again" is clicked', () => {
      function Wrapper() {
        const [shouldThrow, setShouldThrow] = React.useState(true);
        return (
          <ErrorBoundary
            fallback={({ reset }) => (
              <button
                type="button"
                onClick={() => {
                  setShouldThrow(false);
                  reset();
                }}
              >
                custom reset
              </button>
            )}
          >
            {shouldThrow ? <Bomb /> : <Healthy />}
          </ErrorBoundary>
        );
      }

      render(<Wrapper />);

      const resetButton = screen.getByRole('button', { name: 'custom reset' });
      fireEvent.click(resetButton);

      expect(screen.getByText('all good')).toBeTruthy();
    });
  });

  it('renders children normally when nothing throws', () => {
    render(
      <ErrorBoundary>
        <Healthy />
      </ErrorBoundary>,
    );

    expect(screen.getByText('all good')).toBeTruthy();
    expect(screen.queryByText('เกิดข้อผิดพลาด / Something went wrong')).toBeNull();
  });
});
