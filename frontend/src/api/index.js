import { api as httpApi } from './hrApi.js';
import { API_ROUTES, ROLE_PERMISSIONS } from './routes.js';

const useMockApi = import.meta.env.VITE_USE_MOCKS === 'true'
  || (import.meta.env.DEV && import.meta.env.VITE_USE_MOCKS !== 'false');

let mockApiPromise;

function selectedApi() {
  if (!useMockApi) return Promise.resolve(httpApi);
  if (!mockApiPromise) mockApiPromise = import('./mockApi.js').then((module) => module.api);
  return mockApiPromise;
}

function call(section, method) {
  return async (...args) => {
    const api = await selectedApi();
    return api[section][method](...args);
  };
}

export const api = {
  auth: {
    login: call('auth', 'login'),
    logout: call('auth', 'logout'),
    me: call('auth', 'me'),
  },
  employees: {
    list: call('employees', 'list'),
    create: call('employees', 'create'),
    get: call('employees', 'get'),
    update: call('employees', 'update'),
  },
  profileRequests: {
    list: call('profileRequests', 'list'),
    create: call('profileRequests', 'create'),
    update: call('profileRequests', 'update'),
  },
  users: {
    list: call('users', 'list'),
    create: call('users', 'create'),
    update: call('users', 'update'),
  },
};
export { API_ROUTES, ROLE_PERMISSIONS };
