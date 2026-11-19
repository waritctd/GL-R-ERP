import { describe, expect, it } from 'vitest';
import { SCOPE_TYPES } from './scopeTypes.js';

describe('scopeTypes', () => {
  it('declares EMPLOYEE, DEPARTMENT, DIVISION in that exact order — the resolver precedence', () => {
    // ScopeType.java: "declaration order here is that precedence" — EMPLOYEE beats DEPARTMENT beats DIVISION. This
    // test pins the order so a reorder here (which would silently mislabel the precedence explainer) fails loudly.
    expect(SCOPE_TYPES.map((entry) => entry.value)).toEqual(['EMPLOYEE', 'DEPARTMENT', 'DIVISION']);
    expect(SCOPE_TYPES.map((entry) => entry.precedence)).toEqual([1, 2, 3]);
  });
});
