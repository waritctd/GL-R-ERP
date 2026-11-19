import { describe, expect, it, vi } from 'vitest';
import { warnIfBundleSkipped, toUserErrorMessage } from './userMessages.js';

describe('userMessages', () => {
  it('uses the safe fallback for unmapped technical errors', () => {
    const error = new Error('Cannot read properties of undefined');

    expect(toUserErrorMessage(error, 'โหลดข้อมูลไม่สำเร็จ')).toBe('โหลดข้อมูลไม่สำเร็จ');
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
