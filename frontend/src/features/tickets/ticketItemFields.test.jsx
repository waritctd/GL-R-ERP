import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {  } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  applyCatalogPick,
  
  REQUIRED_ITEM_FIELD_LABELS,
  requiredItemFieldErrors,
} from './ticketItemFields.jsx';

describe('REQUIRED_ITEM_FIELD_LABELS', () => {
  it('is exactly brand/model/size — สี and เนื้อผิว are deliberately excluded', () => {
    expect(Object.keys(REQUIRED_ITEM_FIELD_LABELS).sort()).toEqual(['brand', 'model', 'size']);
  });
});

describe('requiredItemFieldErrors', () => {
  it('reports a กรุณากรอก<label> message for every blank required field', () => {
    const errors = requiredItemFieldErrors({ brand: '', model: '', color: 'ขาว', texture: '', size: '' });
    expect(errors).toEqual({
      brand: 'กรุณากรอกยี่ห้อ / โรงงาน',
      model: 'กรุณากรอกชื่อรุ่น / Collection',
      size: 'กรุณากรอกขนาด',
    });
  });
});

describe('applyCatalogPick', () => {
  function mockCatalogProduct(overrides = {}) {
    return {
      priceId: 501,
      productCode: 'BNFJ30126CA',
      factoryName: 'Bode',
      grade: null,
      collection: 'Stone gallary',
      productName: null,
      color: null,
      surface: 'MATT',
      sizeRaw: '600x1200',
      price: 8.8,
      currency: 'USD',
      priceUnit: 'per_sqm',
      sqmPerPiece: null,
      ...overrides,
    };
  }

  it('resolves sqmPerPiece from the catalog row and cross-fills qtySqm from an existing qty', () => {
    const result = applyCatalogPick({ brand: '', qty: 10 }, mockCatalogProduct({ sqmPerPiece: 0.72 }));
    expect(result.sqmPerPiece).toBe(0.72);
    expect(result.qtySqm).toBe('7.200');
  });
});

// shared-module usage pin ───────────────────────────────────────────────── The bug this branch fixes was
// exactly this: TicketCreateModal.jsx had ItemFieldLabel/ItemField/
// CatalogOption/CatalogAutocompleteField/REQUIRED_ITEM_FIELD_LABELS, and TicketDetailPage.jsx had none of it.
describe('shared usage pin — TicketCreateModal.jsx and DealItemsPanel.jsx (the deal page items editor, moved out of TicketDetailPage.jsx 2026-11-16) both import from here, neither re-inlines', () => {
  function readSource(relativePath) {
    return readFileSync(resolve(process.cwd(), relativePath), 'utf8');
  }

  // The create form's item editor moved out of TicketCreateModal.jsx into CreateDealItems.jsx (2026-11-16 short form;
  // shown only to creators who cannot edit items afterwards), so that file is what is pinned now.
  const files = {
    'CreateDealItems.jsx': readSource('src/features/tickets/CreateDealItems.jsx'),
    'DealItemsPanel.jsx': readSource('src/features/tickets/DealItemsPanel.jsx'),
  };

  for (const [name, source] of Object.entries(files)) {
    it(`${name} imports CatalogAutocompleteField, ITEM_FIELD_META and REQUIRED_ITEM_FIELD_LABELS from ticketItemFields.jsx`, () => {
      // A single import statement is not required — just that these names are pulled in from the shared module
      // somewhere in the file's import section.
      const importBlockMatch = source.match(/import\s*\{([\s\S]*?)\}\s*from\s*'\.\/ticketItemFields\.jsx';/);
      expect(importBlockMatch, `${name} has no import from ./ticketItemFields.jsx`).toBeTruthy();
      const importedNames = importBlockMatch[1];
      expect(importedNames).toMatch(/\bCatalogAutocompleteField\b/);
      expect(importedNames).toMatch(/\bREQUIRED_ITEM_FIELD_LABELS\b/);
      expect(importedNames).toMatch(/\bItemField\b/);
      expect(importedNames).toMatch(/\bITEM_FIELD_META\b/);
    });

    it(`${name} does not locally define/re-declare ItemFieldLabel/ItemField/CatalogOption/CatalogAutocompleteField/REQUIRED_ITEM_FIELD_LABELS`, () => {
      // function/const/let/class — not just `function`. A local `const ItemFieldLabel = (...) =>` or `const
      // REQUIRED_ITEM_FIELD_LABELS = {...}` re-inlines exactly as effectively as a `function` declaration would, and
      // the earlier version of this guard only caught the latter.
      const localRedeclaration = /(?:function|const|let|class)\s+(ItemFieldLabel|ItemField|CatalogOption|CatalogAutocompleteField|REQUIRED_ITEM_FIELD_LABELS)\b/;
      const match = source.match(localRedeclaration);
      expect(match, `${name} re-declares ${match?.[1]} locally`).toBeNull();
    });
  }
});
