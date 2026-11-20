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
