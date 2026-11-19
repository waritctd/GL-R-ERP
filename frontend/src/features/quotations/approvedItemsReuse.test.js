import { describe, expect, it } from 'vitest';
import { groupForPickedLabel, locationOrNoteDiffers, reusesApprovedPricesOnly, rowFromApprovedItem } from './approvedItemsReuse.js';

const source = {
  sourceQuotationItemId: 7, lineType: 'TILE', locationLabel: 'ชั้น 1', itemNotes: 'สีตามตัวอย่าง', priceMode: 'NET',
  documentPriceMode: 'NET', documentLanguage: 'TH', leadTimeMinDays: null, leadTimeMaxDays: null, stockSource: null,
};

describe('picked approved line', () => {
  it('keeps the source location heading and note, so it still qualifies for instant approval', () => {
    const groups = [{ groupId: 'g0', label: '' }];
    const picked = groupForPickedLabel(groups, [], source.locationLabel);
    expect(picked.groups[0].label).toBe('ชั้น 1'); // the blank empty group is named, not a second one added
    const row = rowFromApprovedItem(source, 'NET', picked.groupId);
    expect(row.itemNotes).toBe('สีตามตัวอย่าง');
    const locationOf = (it) => picked.groups.find((g) => g.groupId === it.groupId)?.label;
    const args = {
      items: [row], adjustments: [], depositPercent: 50, sourceDepositPercent: 50, reusable: [source],
      docSettings: { priceMode: 'NET', documentLanguage: 'TH' }, locationOf,
    };
    expect(reusesApprovedPricesOnly(args)).toBe(true);
    expect(locationOrNoteDiffers(args)).toBe(false);
    const edited = { ...args, items: [{ ...row, itemNotes: 'อย่างอื่น' }] };
    expect(reusesApprovedPricesOnly(edited)).toBe(false);
    expect(locationOrNoteDiffers(edited)).toBe(true);
  });

  it('reuses an existing group with the same label, else adds a group', () => {
    const groups = [{ groupId: 'a', label: 'ชั้น 1' }, { groupId: 'b', label: 'ชั้น 2' }];
    expect(groupForPickedLabel(groups, [], ' ชั้น 1 ').groupId).toBe('a');
    const added = groupForPickedLabel(groups, [], 'ชั้น 3');
    expect(added.groups).toHaveLength(3);
  });
});
