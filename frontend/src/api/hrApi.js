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
    changePassword: (payload) => apiRequest(API_ROUTES.auth.changePassword, { method: 'POST', body: payload }),
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
  attendance: {
    list: (params) => apiRequest(withQuery(API_ROUTES.attendance.punches, params)),
    importDat: (payload) => apiRequest(API_ROUTES.attendance.importDat, { method: 'POST', body: payload }),
  },
  tickets: {
    list: (params) => apiRequest(withQuery(API_ROUTES.tickets.list, params)),
    create: (payload) => apiRequest(API_ROUTES.tickets.create, { method: 'POST', body: payload }),
    get: (id) => apiRequest(API_ROUTES.tickets.detail(id)),
    submit: (id) => apiRequest(API_ROUTES.tickets.action(id, 'submit'), { method: 'POST' }),
    pickup: (id) => apiRequest(API_ROUTES.tickets.action(id, 'pickup'), { method: 'POST' }),
    proposePrice: (id, payload) => apiRequest(API_ROUTES.tickets.action(id, 'propose-price'), { method: 'POST', body: payload }),
    approve: (id) => apiRequest(API_ROUTES.tickets.action(id, 'approve'), { method: 'POST' }),
    reject: (id, payload) => apiRequest(API_ROUTES.tickets.action(id, 'reject'), { method: 'POST', body: payload }),
    quotation: (id) => apiRequest(API_ROUTES.tickets.action(id, 'quotation'), { method: 'POST' }),
    close: (id) => apiRequest(API_ROUTES.tickets.action(id, 'close'), { method: 'POST' }),
    cancel: (id) => apiRequest(API_ROUTES.tickets.action(id, 'cancel'), { method: 'POST' }),
    editItems: (id, payload) => apiRequest(API_ROUTES.tickets.editItems(id), { method: 'PATCH', body: payload }),
    comment: (id, payload) => apiRequest(API_ROUTES.tickets.action(id, 'comments'), { method: 'POST', body: payload }),
  },
  dashboard: {
    summary: () => apiRequest(API_ROUTES.dashboard.summary),
  },
  notifications: {
    list: async () => {
      const payload = await apiRequest(API_ROUTES.notifications.list);
      return {
        notifications: Array.isArray(payload) ? payload : (payload?.notifications ?? []),
      };
    },
    markRead: (id) => apiRequest(API_ROUTES.notifications.markRead(id), { method: 'PATCH' }),
  },
  commissions: {
    list: (params) => apiRequest(withQuery(API_ROUTES.commissions.list, params)),
    create: (payload) => apiRequest(API_ROUTES.commissions.create, { method: 'POST', body: payload }),
    updateDeductions: (id, payload) => apiRequest(API_ROUTES.commissions.deductions(id), { method: 'PATCH', body: payload }),
    approve: (id) => apiRequest(API_ROUTES.commissions.approve(id), { method: 'POST' }),
    clawback: (id, payload) => apiRequest(API_ROUTES.commissions.clawback(id), { method: 'POST', body: payload }),
    simulate: (payload) => apiRequest(API_ROUTES.commissions.simulator, { method: 'POST', body: payload }),
    payrollReady: (params) => apiRequest(withQuery(API_ROUTES.commissions.payrollReady, params)),
  },
};
