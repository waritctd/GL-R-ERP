import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, it, expect } from 'vitest';
import { MOCK_DEFAULT_DEPOSIT_PERCENT, MOCK_LEGACY_DEPOSIT_PERCENT } from './mockApi.js';
import { stripComments } from './apiSurface.js';

// The mock's blank-percent defaults restate two Java constants; read them out of the source so the
// copies cannot drift. Throws rather than skips when the files move, so the guard cannot quietly
// disable itself; comments are stripped first, so a commented-out declaration does not count.
function readBackendSource(relativePath) {
  for (let dir = process.cwd(), depth = 0; depth < 8; depth += 1, dir = dirname(dir)) {
    const candidate = resolve(dir, relativePath);
    if (existsSync(candidate)) return readFileSync(candidate, 'utf8');
  }
  throw new Error(`could not find ${relativePath} above ${process.cwd()}`);
}

function javaIntConstant(relativePath, name) {
  const match = stripComments(readBackendSource(relativePath)).match(new RegExp(`static final int ${name} = (\\d+);`));
  if (!match) throw new Error(`could not find int ${name} in ${relativePath}`);
  return Number(match[1]);
}

describe('mock deposit-percent defaults match the Java PDFs', () => {
  it.each([
    ['a blank new-engine percent', MOCK_DEFAULT_DEPOSIT_PERCENT,
      'backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationRenderAdapter.java', 'DEFAULT_DEPOSIT_PERCENT'],
    ['a legacy quotation', MOCK_LEGACY_DEPOSIT_PERCENT,
      'backend/src/main/java/th/co/glr/hr/ticket/QuotationRenderer.java', 'LEGACY_DEPOSIT_PERCENT'],
  ])('%s equals the Java constant', (_label, mockValue, javaFile, constant) => {
    expect(mockValue).toBe(javaIntConstant(javaFile, constant));
  });
});
