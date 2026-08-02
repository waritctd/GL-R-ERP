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
    api.priceImport.factories.mockResolvedValue([]);
    api.catalog.prices.mockResolvedValue({ items: [] });
  });

  it('describes the active price list without exposing backend/source terms', async () => {
    render(<CatalogSearchPage user={{ role: 'sales' }} showToast={vi.fn()} />);

    expect(await screen.findByText('ราคาจากรายการราคาที่ใช้งานล่าสุด')).not.toBeNull();
    expect(screen.queryByText(/price list|ACTIVE/i)).toBeNull();
  });
});
