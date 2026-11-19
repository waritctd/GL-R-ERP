import React from 'react';
import { render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Tabs } from './Tabs.jsx';

globalThis.React = React;

const originalScrollIntoView = Element.prototype.scrollIntoView;

const items = [
  { id: 'overview', label: 'ภาพรวม', helper: 'ข้อมูลดีล' },
  { id: 'pricing', label: 'ราคา', helper: 'คำขอราคา' },
  { id: 'documents', label: 'เอกสาร', helper: 'ไฟล์แนบ' },
  { id: 'activity', label: 'กิจกรรม', helper: 'ประวัติ' },
];

afterEach(() => {
  Element.prototype.scrollIntoView = originalScrollIntoView;
  vi.restoreAllMocks();
});

describe('Tabs', () => {

  // Regression coverage for a real incident: ActivityLogPage.jsx shipped `items: [{ value: 'x', label }]`
  // instead of `{ id: 'x', label }` (this component reads `item.id`, not `value` — see
  // fix/activity-log-page-load-failure).
  describe('dev-only missing/duplicate id invariant', () => {
    let consoleError;

    beforeEach(() => {
      consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    });

    afterEach(() => {
      consoleError.mockRestore();
    });

    it('stays silent for correctly-shaped items (id on every item, all unique)', () => {
      render(<Tabs items={items} value="overview" onChange={() => {}} ariaLabel="รายละเอียดดีล" idPrefix="ticket-detail" />);
      expect(consoleError).not.toHaveBeenCalled();
    });

    it('warns loudly when an item is missing id — the exact shape of the real incident', () => {
      const brokenItems = [
        { value: 'summary', label: 'สรุป' },
        { value: 'actions', label: 'การดำเนินการ' },
      ];

      render(<Tabs items={brokenItems} value="summary" onChange={() => {}} ariaLabel="บันทึกการใช้งาน" idPrefix="activity-log" />);

      // Not asserting an exact call count: with every item.id undefined, React ALSO logs its own "two children with
      // the same key" warning for the duplicate `key={item.id}` — that is a welcome side effect of the same
      // underlying bug, not noise to suppress. Find our message among whatever console.error logged.
      const messages = consoleError.mock.calls.map(([message]) => message);
      const ours = messages.find((message) => message.includes('missing an `id`'));
      expect(ours).toBeDefined();
      expect(ours).toContain('2 of 2');
      expect(ours).toContain('activity-log');
    });
  });
});
