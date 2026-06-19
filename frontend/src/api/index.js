import { api as httpApi } from './hrApi.js';
import { api as mockApi } from './mockApi.js';
import { API_ROUTES, ROLE_PERMISSIONS } from './routes.js';

const useMockApi = import.meta.env.VITE_USE_MOCKS === 'true'
  || (import.meta.env.DEV && import.meta.env.VITE_USE_MOCKS !== 'false');

export const api = useMockApi ? mockApi : httpApi;
export { API_ROUTES, ROLE_PERMISSIONS };
