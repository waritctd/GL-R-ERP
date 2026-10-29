import React from 'react';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CatalogSearchPage } from './CatalogSearchPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    priceImport: {
      factories: vi.fn(),
    },
    factoryConfigs: {
      names: vi.fn(),
    },
    catalog: {
      prices: vi.fn(),
    },
  },
}));

vi.mock('../../hooks/useIsMobile.js', () => ({
  useIsMobile: () => false,
}));

describe('CatalogSearchPage audit copy', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Mocked the way Java answers sales/sales_manager: PriceImportController#factories is
    // ceo/import only; FactoryConfigController#names is open to sales + sales_manager.
    api.priceImport.factories.mockRejectedValue(Object.assign(new Error('forbidden'), { status: 403 }));
    api.factoryConfigs.names.mockResolvedValue({ factories: [] });
    api.catalog.prices.mockResolvedValue({ items: [] });
  });

  it('describes the active price list without exposing backend/source terms', async () => {
    render(<CatalogSearchPage user={{ role: 'sales' }} showToast={vi.fn()} />);

    expect(await screen.findByText('ราคาจากรายการราคาที่ใช้งานล่าสุด')).not.toBeNull();
    expect(screen.queryByText(/price list|ACTIVE/i)).toBeNull();
  });

  // RC-03 (role check 2026-10-29, owner ruling): the factory filter was silently empty for sales
  // and sales_manager because it read the ceo/import-only /api/price-import/factories.
  it.each(['sales', 'sales_manager'])('fills the factory filter for %s from the names-only endpoint', async (role) => {
    api.factoryConfigs.names.mockResolvedValue({ factories: [{ id: 42, factoryName: 'Mutina' }] });
    render(<CatalogSearchPage user={{ role }} showToast={vi.fn()} />);

    const option = await screen.findByRole('option', { name: 'Mutina' });
    expect(option.getAttribute('value')).toBe('42');
  });
});
