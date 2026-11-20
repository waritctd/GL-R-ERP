import React from 'react';
import { describe, expect, it, vi, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { SafeForm } from './SafeForm.jsx';

globalThis.React = React;

afterEach(() => {
  vi.restoreAllMocks();
});

// One field, no submit button anywhere -- the exact shape of the bug this primitive exists to kill (see
// SafeForm.jsx's own header comment: /tax-allowance and TicketCreateModal both shipped this to production).
function formWithNoButton(onSubmit, extraProps = {}) {
  return render(
    <SafeForm data-testid="safe-form" onSubmit={onSubmit} {...extraProps}>
      <input aria-label="only field" defaultValue="x" />
    </SafeForm>,
  );
}

function formWithButton(onSubmit, extraProps = {}) {
  return render(
    <SafeForm data-testid="safe-form" onSubmit={onSubmit} {...extraProps}>
      <input aria-label="only field" defaultValue="x" />
      <button type="submit">Go</button>
    </SafeForm>,
  );
}

describe('SafeForm — default (no canSubmit): the submitter guard', () => {
  it('blocks a submitter-less submit (no submit button in the DOM)', () => {
    const onSubmit = vi.fn();
    formWithNoButton(onSubmit);

    fireEvent.submit(screen.getByTestId('safe-form'));

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('lets a real submit-button click through (submitter is the button)', () => {
    // preventDefault in the handler, not just a bare vi.fn: without it, jsdom tries to run its own default
    // form-submission action after onSubmit returns and logs a spurious "Not implemented: HTMLFormElement's
    // requestSubmit method" warning that has nothing to do with what this test checks.
    const onSubmit = vi.fn((event) => event.preventDefault());
    formWithButton(onSubmit);

    fireEvent.click(screen.getByRole('button', { name: 'Go' }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});

// canSubmit is a RESTRICTION, never a permission (see SafeForm.jsx's header comment, and F1/F3 of this task's
// review round): it can only narrow when a submission is allowed, never widen it. null : ...`. Both checks
// below always apply now.
describe('SafeForm — canSubmit view/step gate', () => {
  it('blocks even a real submit-button click when canSubmit is false', () => {
    const onSubmit = vi.fn((event) => event.preventDefault());
    formWithButton(onSubmit, { canSubmit: false });

    fireEvent.click(screen.getByRole('button', { name: 'Go' }));

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('still lets a real submit-button click through when canSubmit is true', () => {
    const onSubmit = vi.fn((event) => event.preventDefault());
    formWithButton(onSubmit, { canSubmit: true });

    fireEvent.click(screen.getByRole('button', { name: 'Go' }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});

// BUG (บันทึกโรงงาน reloads the page): SafeForm only called preventDefault when it BLOCKED a
// submit, so an ALLOWED submit reached the browser's native GET submission (full reload under
// BrowserRouter, in-memory drafts lost) unless each caller remembered its own preventDefault.
// jsdom does not navigate, so the observable is the event itself: `defaultPrevented`, and
// `dispatchEvent`'s return value (false = some listener called preventDefault).
describe('SafeForm — an ALLOWED submit never falls through to native form submission', () => {
  it('prevents the default BEFORE a handler that never calls preventDefault runs', () => {
    let preventedWhenHandlerRan;
    const onSubmit = vi.fn((event) => { preventedWhenHandlerRan = event.nativeEvent.defaultPrevented; });
    formWithButton(onSubmit);

    const proceeded = screen.getByTestId('safe-form').dispatchEvent(
      new SubmitEvent('submit', { bubbles: true, cancelable: true, submitter: screen.getByRole('button', { name: 'Go' }) }),
    );

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(preventedWhenHandlerRan).toBe(true);
    expect(proceeded).toBe(false);
  });
});
