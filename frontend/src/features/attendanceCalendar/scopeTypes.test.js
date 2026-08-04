import { describe, expect, it } from 'vitest';
import { SCOPE_TYPES, scopeIdLabel, scopeTypeLabel } from './scopeTypes.js';

describe('scopeTypes', () => {
  it('declares EMPLOYEE, DEPARTMENT, DIVISION in that exact order — the resolver precedence', () => {
    // ScopeType.java: "declaration order here is that precedence" — EMPLOYEE beats DEPARTMENT
    // beats DIVISION. This test pins the order so a reorder here (which would silently mislabel
    // the precedence explainer) fails loudly.
    expect(SCOPE_TYPES.map((entry) => entry.value)).toEqual(['EMPLOYEE', 'DEPARTMENT', 'DIVISION']);
    expect(SCOPE_TYPES.map((entry) => entry.precedence)).toEqual([1, 2, 3]);
  });

  it('labels every scope type in Thai', () => {
    expect(scopeTypeLabel('EMPLOYEE')).toBe('พนักงานรายคน');
    expect(scopeTypeLabel('DEPARTMENT')).toBe('แผนก');
    expect(scopeTypeLabel('DIVISION')).toBe('ฝ่าย');
  });

  it('falls back to the raw value for an unknown scope type', () => {
    expect(scopeTypeLabel('UNKNOWN')).toBe('UNKNOWN');
    expect(scopeTypeLabel(null)).toBe('-');
  });

  it('gives each scope type its own id-field label', () => {
    expect(scopeIdLabel('EMPLOYEE')).toBe('รหัสพนักงาน');
    expect(scopeIdLabel('DEPARTMENT')).toBe('รหัสแผนก');
    expect(scopeIdLabel('DIVISION')).toBe('รหัสฝ่าย');
    expect(scopeIdLabel('UNKNOWN')).toBe('รหัสขอบเขต');
  });
});
