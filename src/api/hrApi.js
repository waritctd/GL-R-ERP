import { apiRequest } from './client.js';
import { API_ROUTES } from './routes.js';

function withQuery(path, params = {}) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, value);
  });
  const text = query.toString();
  return text ? `${path}?${text}` : path;
}

export const api = {
  auth: {
    login: (payload) => apiRequest(API_ROUTES.auth.login, { method: 'POST', body: payload }),
    logout: () => apiRequest(API_ROUTES.auth.logout, { method: 'POST' }),
    me: () => apiRequest(API_ROUTES.auth.me),
  },
  employees: {
    list: (params) => apiRequest(withQuery(API_ROUTES.employees.list, params)),
    create: (payload) => apiRequest(API_ROUTES.employees.create, { method: 'POST', body: payload }),
    get: (id) => apiRequest(API_ROUTES.employees.detail(id)),
    update: (id, payload) => apiRequest(API_ROUTES.employees.detail(id), { method: 'PATCH', body: payload }),
  },
  profileRequests: {
    list: () => apiRequest(API_ROUTES.profileRequests.list),
    create: (payload) => apiRequest(API_ROUTES.profileRequests.create, { method: 'POST', body: payload }),
    update: (id, payload) => apiRequest(API_ROUTES.profileRequests.detail(id), { method: 'PATCH', body: payload }),
  },
  users: {
    list: () => apiRequest(API_ROUTES.users.list),
    create: (payload) => apiRequest(API_ROUTES.users.create, { method: 'POST', body: payload }),
    update: (id, payload) => apiRequest(API_ROUTES.users.detail(id), { method: 'PATCH', body: payload }),
  },
};
