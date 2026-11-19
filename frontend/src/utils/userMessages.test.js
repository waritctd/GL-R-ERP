import { describe, expect, it, vi } from 'vitest';
import { warnIfBundleSkipped, sanitizeToastMessage, toUserErrorDescription, toUserErrorMessage } from './userMessages.js';

describe('userMessages', () => {
  it('maps the known employee-linking implementation error to approved Thai copy', () => {
    const error = new Error('User is not linked to an employee');

    expect(toUserErrorMessage(error)).toBe('ยังเชื่อมบัญชีนี้กับข้อมูลพนักงานไม่ได้');
    expect(toUserErrorDescription(error)).toContain('ติดต่อฝ่ายบุคคล');
  });

  it('uses the safe fallback for unmapped technical errors', () => {
    const error = new Error('Cannot read properties of undefined');

    expect(toUserErrorMessage(error, 'โหลดข้อมูลไม่สำเร็จ')).toBe('โหลดข้อมูลไม่สำเร็จ');
  });

  it('logs the technical toast detail while returning safe user copy', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});

    expect(sanitizeToastMessage('error', 'User is not linked to an employee')).toBe('ยังเชื่อมบัญชีนี้กับข้อมูลพนักงานไม่ได้');
    expect(spy).toHaveBeenCalledWith('Suppressed technical error toast:', 'User is not linked to an employee');

    spy.mockRestore();
  });

  it('warnIfBundleSkipped toasts a warning naming the skipped parts, and is silent otherwise', () => {
    const showToast = vi.fn();
    warnIfBundleSkipped({ bundleSkipped: 'po,quotation' }, showToast);
    expect(showToast).toHaveBeenCalledWith('warning', 'แนบ PO และ ใบเสนอราคา ไม่ครบ กรุณาตรวจสอบไฟล์ก่อนส่งลูกค้า');
    showToast.mockClear();
    warnIfBundleSkipped({ bundleSkipped: '' }, showToast);
    warnIfBundleSkipped(new Blob(['x']), showToast);
    warnIfBundleSkipped({ bundleSkipped: 'po' }, undefined);
    expect(showToast).not.toHaveBeenCalled();
  });
});
