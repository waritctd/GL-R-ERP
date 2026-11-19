import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { Button, buttonVariants } from './Button.jsx';

globalThis.React = React;

describe('Button', () => {
  it('fires onClick and respects disabled', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>คลิก</Button>);

    fireEvent.click(screen.getByRole('button', { name: 'คลิก' }));
    expect(onClick).toHaveBeenCalledTimes(1);

    render(<Button disabled onClick={onClick}>ปิดอยู่</Button>);
    const disabledButton = screen.getByRole('button', { name: 'ปิดอยู่' });
    expect(disabledButton.disabled).toBe(true);
    fireEvent.click(disabledButton);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('renders a loading state that is busy, disabled, and width-preserving', () => {
    const onClick = vi.fn();
    const { container } = render(<Button loading onClick={onClick}>บันทึก</Button>);
    const button = screen.getByRole('button', { name: 'บันทึก' });

    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.textContent).toContain('บันทึก');
    expect(container.querySelector('.button-loading-spinner')).toBeTruthy();

    fireEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('rejects icon buttons without an accessible name', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      expect(() => render(<Button variant="icon">i</Button>)).toThrow(/accessible name/);
    } finally {
      consoleError.mockRestore();
    }
  });
});

// cva answers a variant it does not know with the base classes alone, so the
// element renders with the browser's own button face and nothing reports it.
// That is how variant="ghost" reached develop unnoticed.
describe('Button variant check', () => {
  const KNOWN_VARIANTS = ['primary', 'secondary', 'success', 'danger', 'text', 'icon'];
  // null skips the 'primary' default and reaches cva as "no variant". false is
  // what `variant={flag && 'danger'}` passes. 'constructor' is inherited from
  // Object.prototype, so a plain key lookup would call it known.
  const UNKNOWN_VARIANTS = ['ghost', null, false, 'constructor'];

  let consoleError;

  beforeEach(() => {
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    consoleError.mockRestore();
    vi.unstubAllEnvs();
  });

  it.each(UNKNOWN_VARIANTS)('rejects the unknown variant %s on <Button> in development', (variant) => {
    expect(() => render(<Button variant={variant}>ย้อนกลับ</Button>)).toThrow(`variant="${variant}"`);
  });

  it('reports an unknown variant once in production and still renders the button', () => {
    vi.stubEnv('DEV', false);
    render(<Button variant="ghost">ย้อนกลับ</Button>);

    expect(screen.getByRole('button', { name: 'ย้อนกลับ' }).className).toContain('inline-flex');
    expect(consoleError).toHaveBeenCalledTimes(1);
    expect(consoleError).toHaveBeenCalledWith(expect.stringContaining('variant="ghost"'));
  });

  it.each(KNOWN_VARIANTS)('accepts the %s variant on both entry points', (variant) => {
    render(<Button variant={variant} aria-label="ปุ่ม">i</Button>);

    expect(screen.getByRole('button', { name: 'ปุ่ม' })).toBeTruthy();
    expect(buttonVariants({ variant })).toContain('inline-flex');
    expect(consoleError).not.toHaveBeenCalled();
  });
});
