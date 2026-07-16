import { describe, it, expect } from 'vitest';
import { api as hrApi } from './hrApi.js';
import { api as mockApi } from './mockApi.js';

// Guards the mock/real API contract at the level of *method names* — see #201.
//
// The direction that matters is hrApi → mockApi: every endpoint the app can call
// must exist in the mock, or `VITE_USE_MOCKS=true` verification hits
// "TypeError: api.x.y is not a function" on click. That is exactly how
// `priceImport.uploadAndCommit` shipped missing while the page still *loaded*
// fine (read paths were mocked, only the mutation was absent).
//
// This asserts names, not behaviour, and deliberately so: it is the cheap,
// durable half. DTO shapes and — critically — authorization are NOT covered
// here. Mock authz is not authoritative; verify permission behaviour against
// the Java service. See CLAUDE.md "Mock API contract".

const KNOWN_GAPS = {
  // The whole `documents` namespace (8 methods) is absent from the mock. Its only
  // consumer, frontend/src/features/documents/DocumentPage.jsx, is never imported
  // and never routed — the page is orphaned, so nothing can call these today.
  // This is a known gap, not an oversight: the moment anyone wires DocumentPage
  // up, the mock needs these methods or the page breaks in mock mode. Remove this
  // exemption at that point rather than deleting the assertion.
  documents: 'DocumentPage.jsx is unrouted and unimported; implement these in mockApi before wiring the page up.',
};

function methodPaths(apiObject) {
  return Object.entries(apiObject).flatMap(([namespace, methods]) =>
    Object.keys(methods).map((method) => `${namespace}.${method}`)
  );
}

function isImplemented(apiObject, namespace, method) {
  return typeof apiObject[namespace]?.[method] === 'function';
}

describe('mockApi / hrApi method contract', () => {
  it('mockApi implements every method hrApi exposes, except documented gaps', () => {
    const missing = methodPaths(hrApi).filter((path) => {
      const [namespace, method] = path.split('.');
      if (KNOWN_GAPS[namespace]) return false;
      return !isImplemented(mockApi, namespace, method);
    });

    expect(missing).toEqual([]);
  });

  it('mockApi has no dead methods that hrApi does not expose', () => {
    const dead = methodPaths(mockApi).filter((path) => {
      const [namespace, method] = path.split('.');
      return !isImplemented(hrApi, namespace, method);
    });

    expect(dead).toEqual([]);
  });

  it('every KNOWN_GAPS entry is a real hrApi namespace with a written reason', () => {
    for (const [namespace, reason] of Object.entries(KNOWN_GAPS)) {
      expect(hrApi[namespace], `KNOWN_GAPS lists "${namespace}", which hrApi does not have`).toBeDefined();
      expect(reason.length, `KNOWN_GAPS["${namespace}"] needs a reason`).toBeGreaterThan(20);
    }
  });
});
