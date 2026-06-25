import { API_ROUTES, ROLE_PERMISSIONS } from './routes.js';
import { api as hrApi } from './hrApi.js';
import { api as mockApiImpl } from './mockApi.js';

const useMocks = import.meta.env.VITE_USE_MOCKS === 'true';
export const api = useMocks ? mockApiImpl : hrApi;

export { API_ROUTES, ROLE_PERMISSIONS };
