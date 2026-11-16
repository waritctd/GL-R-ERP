import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App.jsx';
import { MaintenanceGate } from './app/MaintenanceGate.jsx';
import { ErrorBoundary } from './components/common/ErrorBoundary.jsx';
import { queryClient } from './api/queryClient.js';
import { purgeLegacyDealDrafts } from './app/purgeLegacyDealDrafts.js';
import './index.css';

// Once per browser: drop the removed create form's saved drafts (they can hold customer details).
purgeLegacyDealDrafts();

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <MaintenanceGate>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <ErrorBoundary>
            <App />
          </ErrorBoundary>
        </BrowserRouter>
      </QueryClientProvider>
    </MaintenanceGate>
  </React.StrictMode>,
);
