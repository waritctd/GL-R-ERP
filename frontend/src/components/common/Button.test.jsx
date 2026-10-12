import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { Button, buttonVariants } from './Button.jsx';

globalThis.React = React;

describe('Button', () => {
  it('renders children', () => {
    render(<Button>บันทึก</Button>);
    expect(screen.getByRole('button', { name: 'บันทึก' })).toBeTruthy();
  });

  it('uses primary md classes by default', () => {
    render(<Button>ค่าเริ่มต้น</Button>);
    const button = screen.getByRole('button', { name: 'ค่าเริ่มต้น' });

    expect(button.type).toBe('button');
    expect(button.className).toContain('inline-flex');
    expect(button.className).toContain('min-h-[38px]');
    expect(button.className).toContain('bg-primary');
    expect(button.className).toContain('text-surface');
    expect(button.className).toContain('px-4');
  });

  it('applies variant classes', () => {
    render(
      <>
        <Button variant="secondary">รอง</Button>
        <Button variant="success">สำเร็จ</Button>
        <Button variant="danger">ลบ</Button>
        <Button variant="text">ลิงก์</Button>
        <Button variant="icon" aria-label="ตั้งค่า">i</Button>
      </>,
    );

    expect(screen.getByRole('button', { name: 'รอง' }).className).toContain('border-border-input');
    expect(screen.getByRole('button', { name: 'สำเร็จ' }).className).toContain('bg-success');
    expect(screen.getByRole('button', { name: 'ลบ' }).className).toContain('text-danger');
    expect(screen.getByRole('button', { name: 'ลิงก์' }).className).toContain('border-0');
    expect(screen.getByRole('button', { name: 'ตั้งค่า' }).className).toContain('w-11');
  });

  it('applies small size classes', () => {
    render(
      <>
        <Button size="sm">เล็ก</Button>
        <Button variant="icon" size="sm" aria-label="ไอคอนเล็ก">i</Button>
      </>,
    );

    expect(screen.getByRole('button', { name: 'เล็ก' }).className).toContain('min-h-[32px]');
    expect(screen.getByRole('button', { name: 'เล็ก' }).className).toContain('text-sm');
    expect(screen.getByRole('button', { name: 'ไอคอนเล็ก' }).className).toContain('w-9');
    expect(screen.getByRole('button', { name: 'ไอคอนเล็ก' }).className).toContain('min-h-[36px]');
  });

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

  it('uses title as the accessible name fallback for icon buttons', () => {
    render(<Button variant="icon" title="รีเฟรช">i</Button>);

    expect(screen.getByRole('button', { name: 'รีเฟรช' }).getAttribute('aria-label')).toBe('รีเฟรช');
  });

  it('uses explicit accessible labels correctly for icon buttons', () => {
    render(
      <>
        <Button variant="icon" aria-label="เปิดตัวกรอง">i</Button>
        <span id="save-label">บันทึกด่วน</span>
        <Button variant="icon" aria-labelledby="save-label">s</Button>
      </>,
    );

    expect(screen.getByRole('button', { name: 'เปิดตัวกรอง' }).getAttribute('aria-label')).toBe('เปิดตัวกรอง');
    expect(screen.getByRole('button', { name: 'บันทึกด่วน' }).getAttribute('aria-labelledby')).toBe('save-label');
  });

  it('keeps caller-provided labels stable while loading', () => {
    render(
      <Button loading aria-label="บันทึกข้อมูลพนักงาน">
        กำลังบันทึก
      </Button>,
    );

    const button = screen.getByRole('button', { name: 'บันทึกข้อมูลพนักงาน' });
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-busy')).toBe('true');
  });

  it('rejects icon buttons without an accessible name', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      expect(() => render(<Button variant="icon">i</Button>)).toThrow(/accessible name/);
    } finally {
      consoleError.mockRestore();
    }
  });

  it('does not suppress the global focus-visible contract locally', () => {
    render(<Button>โฟกัส</Button>);
    const button = screen.getByRole('button', { name: 'โฟกัส' });

    expect(button.className).not.toMatch(/outline-none|focus:outline-none/);
  });

  it('merges caller className last', () => {
    render(<Button className="px-2 min-h-[40px]">กำหนดเอง</Button>);
    const button = screen.getByRole('button', { name: 'กำหนดเอง' });

    expect(button.className).toContain('px-2');
    expect(button.className).not.toContain('px-4');
    expect(button.className).toContain('min-h-[40px]');
    expect(button.className).not.toContain('min-h-[38px]');
  });
});

// cva answers a variant it does not know with the base classes alone, so the
// element renders with the browser's own button face and nothing reports it.
// That is how variant="ghost" reached develop unnoticed.
describe('Button variant check', () => {
  const KNOWN_VARIANTS = ['primary', 'secondary', 'success', 'danger', 'text', 'icon'];
  // null skips the 'primary' default and reaches cva as "no variant".
  // 'constructor' is inherited from Object.prototype, so a plain key lookup
  // would call it known.
  const UNKNOWN_VARIANTS = ['ghost', null, 'constructor'];

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

  it.each(UNKNOWN_VARIANTS)('rejects the unknown variant %s on a direct buttonVariants call in development', (variant) => {
    expect(() => buttonVariants({ variant })).toThrow(`variant="${variant}"`);
  });

  it('lists the known variants in the error', () => {
    for (const known of KNOWN_VARIANTS) {
      expect(() => buttonVariants({ variant: 'ghost' })).toThrow(known);
    }
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

  it('keeps the buttonVariants call shape: size passes through and an omitted variant is primary', () => {
    expect(buttonVariants({ variant: 'secondary', size: 'sm' })).toContain('border-border-input');
    expect(buttonVariants({ variant: 'secondary', size: 'sm' })).toContain('min-h-[32px]');
    expect(buttonVariants({ size: 'sm' })).toContain('bg-primary');
    expect(buttonVariants()).toContain('bg-primary');
    expect(consoleError).not.toHaveBeenCalled();
  });
});
