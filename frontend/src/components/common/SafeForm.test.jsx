import React from 'react';
import { describe, expect, it, vi, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { SafeForm } from './SafeForm.jsx';

globalThis.React = React;

afterEach(() => {
  vi.restoreAllMocks();
});

// One field, no submit button anywhere -- the exact shape of the bug this primitive exists to
// kill (see SafeForm.jsx's own header comment: /tax-allowance and TicketCreateModal both shipped
// this to production). `fireEvent.submit(form)` is the idiomatic jsdom way to dispatch a
// submitter-less 'submit' event -- verified empirically (see this task's PR description) that
// testing-library's `fireEvent.submit` creates a plain `Event`, not a `SubmitEvent`, so
// `event.submitter` comes back `undefined`, exactly like a real browser's `submitter: null` for
// this loose-equality check. It is NOT a faithful reproduction of a real Enter keypress (jsdom
// does not implement implicit submission at all -- see e2e/implicit-submission.spec.js for the
// layer that actually does), but it IS a faithful reproduction of "a submit event with no
// submitter", which is the only thing this guard looks at.
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

// A submitter-bearing dispatch that doesn't require a real button in the DOM -- for proving
// canSubmit=true still requires a submitter (it is a restriction, not a permission; see
// SafeForm.jsx's header comment) without needing a `formWithButton` fixture. `new SubmitEvent`
// with an explicit `submitter` is constructible and dispatchable under jsdom (verified in this
// task's own investigation) and is picked up by React's onSubmit exactly like a real click is.
function dispatchWithSubmitter(form) {
  form.dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true, submitter: document.createElement('button') }));
}

describe('SafeForm — default (no canSubmit): the submitter guard', () => {
  it('blocks a submitter-less submit (no submit button in the DOM)', () => {
    const onSubmit = vi.fn();
    formWithNoButton(onSubmit);

    fireEvent.submit(screen.getByTestId('safe-form'));

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('lets a real submit-button click through (submitter is the button)', () => {
    // preventDefault in the handler, not just a bare vi.fn(): without it, jsdom tries to run its
    // own (unimplemented) default form-submission action after onSubmit returns and logs a
    // spurious "Not implemented: HTMLFormElement's requestSubmit() method" warning that has
    // nothing to do with what this test checks.
    const onSubmit = vi.fn((event) => event.preventDefault());
    formWithButton(onSubmit);

    fireEvent.click(screen.getByRole('button', { name: 'Go' }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('warns in dev, naming the form, when it blocks -- so a developer sees why nothing happened', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onSubmit = vi.fn();
    formWithNoButton(onSubmit, { id: 'probe-form' });

    fireEvent.submit(screen.getByTestId('safe-form'));

    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0][0]).toContain('#probe-form');
    expect(warnSpy.mock.calls[0][0]).toContain('SafeForm');
  });

  it('is inert, not throwing, when it blocks', () => {
    const onSubmit = vi.fn();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    formWithNoButton(onSubmit);

    expect(() => fireEvent.submit(screen.getByTestId('safe-form'))).not.toThrow();
  });
});

describe('SafeForm — allowSubmitterlessSubmit escape hatch', () => {
  it('lets a submitter-less submit through when explicitly allowed', () => {
    const onSubmit = vi.fn();
    formWithNoButton(onSubmit, { allowSubmitterlessSubmit: true });

    fireEvent.submit(screen.getByTestId('safe-form'));

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('does not warn once the escape hatch is used -- that path is intentional, not a mistake', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onSubmit = vi.fn();
    formWithNoButton(onSubmit, { allowSubmitterlessSubmit: true });

    fireEvent.submit(screen.getByTestId('safe-form'));

    expect(warnSpy).not.toHaveBeenCalled();
  });
});

// canSubmit is a RESTRICTION, never a permission (see SafeForm.jsx's header comment, and F1/F3 of
// this task's review round): it can only narrow when a submission is allowed, never widen it. An
// earlier version of this file asserted the opposite -- that canSubmit=true bypasses the
// submitter guard -- which was wrong: TaxAllowanceForm's canSubmit={isReview} is true on REVIEW
// even when readOnly (reachable via TaxAllowancePage's own advertised `?view=review` deep link),
// and REVIEW's only submit button is `readOnly ? null : ...`. That state is safe today only
// because REVIEW happens to render no input fields at all while readOnly -- "safe by accident of
// what a view currently renders" is exactly the failure mode SafeForm exists to close, so the
// bypass had to go. Both checks below always apply now.
describe('SafeForm — canSubmit view/step gate', () => {
  it('blocks even a real submit-button click when canSubmit is false', () => {
    const onSubmit = vi.fn((event) => event.preventDefault());
    formWithButton(onSubmit, { canSubmit: false });

    fireEvent.click(screen.getByRole('button', { name: 'Go' }));

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('still blocks a submitter-less submit when canSubmit is true -- canSubmit does not bypass the submitter guard', () => {
    const onSubmit = vi.fn();
    formWithNoButton(onSubmit, { canSubmit: true });

    fireEvent.submit(screen.getByTestId('safe-form'));

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('lets a submit through when canSubmit is true AND a submitter is present, even with no real button in the DOM', () => {
    const onSubmit = vi.fn();
    const { container } = formWithNoButton(onSubmit, { canSubmit: true });

    dispatchWithSubmitter(container.querySelector('form'));

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('still lets a real submit-button click through when canSubmit is true', () => {
    const onSubmit = vi.fn((event) => event.preventDefault());
    formWithButton(onSubmit, { canSubmit: true });

    fireEvent.click(screen.getByRole('button', { name: 'Go' }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('warns in dev when it blocks on canSubmit=false, distinctly from the submitter-guard message', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onSubmit = vi.fn((event) => event.preventDefault());
    formWithButton(onSubmit, { canSubmit: false, id: 'gated-form' });

    fireEvent.click(screen.getByRole('button', { name: 'Go' }));

    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0][0]).toContain('canSubmit is false');
  });

  it('warns with the submitter-guard message, not the canSubmit one, when canSubmit is true but there is no submitter', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onSubmit = vi.fn();
    formWithNoButton(onSubmit, { canSubmit: true, id: 'no-submitter-form' });

    fireEvent.submit(screen.getByTestId('safe-form'));

    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0][0]).toContain('no submitter');
  });
});

describe('SafeForm — prop forwarding', () => {
  it('forwards id, noValidate and className to the underlying <form>', () => {
    render(
      <SafeForm data-testid="safe-form" id="my-form" noValidate className="grid gap-4" onSubmit={() => {}}>
        <input aria-label="field" />
      </SafeForm>,
    );

    const form = screen.getByTestId('safe-form');
    expect(form.tagName).toBe('FORM');
    expect(form.id).toBe('my-form');
    expect(form.noValidate).toBe(true);
    expect(form.className).toContain('grid');
    expect(form.className).toContain('gap-4');
  });

  it('forwards a ref to the underlying <form> DOM node', () => {
    const ref = React.createRef();
    render(
      <SafeForm ref={ref} onSubmit={() => {}}>
        <input aria-label="field" />
      </SafeForm>,
    );

    expect(ref.current).toBeInstanceOf(HTMLFormElement);
  });

  it('renders children', () => {
    render(
      <SafeForm onSubmit={() => {}}>
        <p>form content</p>
      </SafeForm>,
    );

    expect(screen.getByText('form content')).toBeTruthy();
  });
});
