import { describe, expect, it } from 'vitest';
import { formatAddress } from './format.js';

describe('formatAddress', () => {
  it('joins all four parts, not just line1', () => {
    expect(formatAddress({
      line1: '18/9 ซอย 9 ถ.สุขุมวิท',
      district: 'บางนา',
      province: 'กรุงเทพมหานคร',
      postalCode: '10111',
    })).toBe('18/9 ซอย 9 ถ.สุขุมวิท บางนา กรุงเทพมหานคร 10111');
  });

  // mockApi.createEmployee seeds district/province/postalCode as '' — joining
  // blindly would produce "18/9 ถ.สุขุมวิท   " with trailing separators.
  it('drops empty parts instead of leaving stray separators', () => {
    expect(formatAddress({ line1: '18/9 ถ.สุขุมวิท', district: '', province: '', postalCode: '' }))
      .toBe('18/9 ถ.สุขุมวิท');
  });

  it('trims whitespace-only parts', () => {
    expect(formatAddress({ line1: '18/9', district: '   ', province: 'นนทบุรี', postalCode: null }))
      .toBe('18/9 นนทบุรี');
  });

  it('falls back to a dash when there is no address at all', () => {
    expect(formatAddress(null)).toBe('-');
    expect(formatAddress(undefined)).toBe('-');
    expect(formatAddress({})).toBe('-');
    expect(formatAddress({ line1: '', district: '', province: '', postalCode: '' })).toBe('-');
  });
});
