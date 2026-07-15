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
    devices: () => apiRequest(API_ROUTES.attendance.devices),
  },
  overtime: {
    list: (params) => apiRequest(withQuery(API_ROUTES.overtime.list, params)),
    employees: () => apiRequest(API_ROUTES.overtime.employees),
    create: (payload) => apiRequest(API_ROUTES.overtime.create, { method: 'POST', body: payload }),
    approve: (id, payload = {}) => apiRequest(API_ROUTES.overtime.approve(id), { method: 'POST', body: payload }),
    reject: (id, payload = {}) => apiRequest(API_ROUTES.overtime.reject(id), { method: 'POST', body: payload }),
    cancel: (id, payload = {}) => apiRequest(API_ROUTES.overtime.cancel(id), { method: 'POST', body: payload }),
  },
  leave: {
    list: (params) => apiRequest(withQuery(API_ROUTES.leave.list, params)),
    employees: () => apiRequest(API_ROUTES.leave.employees),
    types: () => apiRequest(API_ROUTES.leave.types),
    balances: (params) => apiRequest(withQuery(API_ROUTES.leave.balances, params)),
    create: async (payload) => {
      if (!Object.prototype.hasOwnProperty.call(payload, 'attachmentFile')) {
        return apiRequest(API_ROUTES.leave.create, { method: 'POST', body: payload });
      }
      const formData = new FormData();
      if (payload.employeeId) formData.append('employeeId', String(payload.employeeId));
      formData.append('leaveTypeCode', payload.leaveTypeCode);
      formData.append('startDate', payload.startDate);
      formData.append('endDate', payload.endDate);
      formData.append('reason', payload.reason);
      if (payload.attachmentFile) formData.append('attachment', payload.attachmentFile);
      const res = await fetch(API_ROUTES.leave.create, {
        method: 'POST',
        credentials: 'include',
        body: formData,
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || 'ส่งคำขอลาไม่สำเร็จ');
      }
      return res.json();
    },
    approve: (id, payload = {}) => apiRequest(API_ROUTES.leave.approve(id), { method: 'POST', body: payload }),
    reject: (id, payload = {}) => apiRequest(API_ROUTES.leave.reject(id), { method: 'POST', body: payload }),
    cancel: (id, payload = {}) => apiRequest(API_ROUTES.leave.cancel(id), { method: 'POST', body: payload }),
  },
  tickets: {
    list: (params) => apiRequest(withQuery(API_ROUTES.tickets.list, params)),
    create: (payload) => apiRequest(API_ROUTES.tickets.create, { method: 'POST', body: payload }),
    get: (id) => apiRequest(API_ROUTES.tickets.detail(id)),
    submit: (id) => apiRequest(API_ROUTES.tickets.action(id, 'submit'), { method: 'POST' }),
    pickup: (id) => apiRequest(API_ROUTES.tickets.action(id, 'pickup'), { method: 'POST' }),
    proposePrice: (id, payload) => apiRequest(API_ROUTES.tickets.action(id, 'propose-price'), { method: 'POST', body: payload }),
    calculatePrices: (id) => apiRequest(API_ROUTES.tickets.action(id, 'calculate-prices'), { method: 'POST' }),
    approve: (id) => apiRequest(API_ROUTES.tickets.action(id, 'approve'), { method: 'POST' }),
    reject: (id, payload) => apiRequest(API_ROUTES.tickets.action(id, 'reject'), { method: 'POST', body: payload }),
    quotation: (id) => apiRequest(API_ROUTES.tickets.action(id, 'quotation'), { method: 'POST' }),
    close: (id) => apiRequest(API_ROUTES.tickets.action(id, 'close'), { method: 'POST' }),
    cancel: (id) => apiRequest(API_ROUTES.tickets.action(id, 'cancel'), { method: 'POST' }),
    editItems: (id, payload) => apiRequest(API_ROUTES.tickets.editItems(id), { method: 'PATCH', body: payload }),
    comment: (id, payload) => apiRequest(API_ROUTES.tickets.action(id, 'comments'), { method: 'POST', body: payload }),
    overrideItemPrice: (id, itemId, payload) => apiRequest(`/api/tickets/${id}/items/${itemId}/price-override`, { method: 'PUT', body: payload }),
    createDocDraft: (id, payload) => apiRequest(API_ROUTES.tickets.createDocDraft(id), { method: 'POST', body: payload }),
    listDocs: (id) => apiRequest(API_ROUTES.tickets.listDocs(id)),
    revision: (id, payload) => apiRequest(API_ROUTES.tickets.revision(id), { method: 'POST', body: payload }),
    downloadRemainingInvoice: async (id) => {
      const res = await fetch(API_ROUTES.depositNotices.remainingInvoiceFile(id), { credentials: 'include' });
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    },
    confirmCustomer: (id) => apiRequest(API_ROUTES.tickets.action(id, 'confirm-customer'), { method: 'POST' }),
    issueDepositNotice: (id) => apiRequest(API_ROUTES.tickets.action(id, 'deposit-notice'), { method: 'POST' }),
    confirmDepositPaid: (id) => apiRequest(API_ROUTES.tickets.action(id, 'deposit-paid'), { method: 'POST' }),
    issueImportRequest: (id) => apiRequest(API_ROUTES.tickets.action(id, 'import-request'), { method: 'POST' }),
    markIrSent: (id) => apiRequest(API_ROUTES.tickets.action(id, 'ir-sent'), { method: 'POST' }),
    markShipping: (id) => apiRequest(API_ROUTES.tickets.action(id, 'shipping'), { method: 'POST' }),
    markGoodsReceived: (id) => apiRequest(API_ROUTES.tickets.action(id, 'goods-received'), { method: 'POST' }),
    confirmFinalPayment: (id) => apiRequest(API_ROUTES.tickets.action(id, 'final-payment'), { method: 'POST' }),
    downloadQuotationXlsx: async (id, quotationId) => {
      const res = await fetch(API_ROUTES.tickets.quotationFile(id, quotationId, 'xlsx'), { credentials: 'include' });
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    },
    downloadQuotationPdf: async (id, quotationId) => {
      const res = await fetch(API_ROUTES.tickets.quotationFile(id, quotationId, 'pdf'), { credentials: 'include' });
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    },
  },
  depositNotices: {
    noteTemplates: () => apiRequest(API_ROUTES.depositNotices.noteTemplates),
    get: (id) => apiRequest(API_ROUTES.depositNotices.get(id)),
    update: (id, payload) => apiRequest(API_ROUTES.depositNotices.update(id), { method: 'PUT', body: payload }),
    preview: (id) => apiRequest(API_ROUTES.depositNotices.preview(id), { method: 'POST' }),
    issue: (id) => apiRequest(API_ROUTES.depositNotices.issue(id), { method: 'POST' }),
    downloadXlsx: async (id) => {
      const res = await fetch(API_ROUTES.depositNotices.file(id, 'xlsx'), { credentials: 'include' });
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    },
    downloadPdf: async (id) => {
      const res = await fetch(API_ROUTES.depositNotices.file(id, 'pdf'), { credentials: 'include' });
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    },
    listByTicket: (ticketId) => apiRequest(API_ROUTES.tickets.listDocs(ticketId)),
    createDraft: (ticketId, payload) => apiRequest(API_ROUTES.tickets.createDocDraft(ticketId), { method: 'POST', body: payload }),
  },
  documents: {
    noteTemplates: () => apiRequest(API_ROUTES.documents.noteTemplates),
    get: (id) => apiRequest(API_ROUTES.documents.get(id)),
    update: (id, payload) => apiRequest(API_ROUTES.documents.update(id), { method: 'PUT', body: payload }),
    preview: async (id) => {
      const res = await fetch(API_ROUTES.documents.preview(id), { method: 'POST', credentials: 'include' });
      if (!res.ok) throw new Error('Preview failed');
      return res.text();
    },
    issue: (id) => apiRequest(API_ROUTES.documents.issue(id), { method: 'POST' }),
    downloadXlsx: async (id) => {
      const res = await fetch(API_ROUTES.documents.file(id, 'xlsx'), { credentials: 'include' });
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    },
    listByTicket: (ticketId) => apiRequest(API_ROUTES.tickets.listDocs(ticketId)),
    createDraft: (ticketId, payload) => apiRequest(API_ROUTES.tickets.createDocDraft(ticketId), { method: 'POST', body: payload }),
  },
  catalog: {
    search: (q) => apiRequest(API_ROUTES.catalog.search(q ?? '')),
    prices: (q, factoryId, limit) => apiRequest(API_ROUTES.catalog.prices(q, factoryId, limit)),
    addProduct: (input) => apiRequest(API_ROUTES.catalog.pricesBase, { method: 'POST', body: input }),
    updateProduct: (priceId, input) => apiRequest(API_ROUTES.catalog.price(priceId), { method: 'PUT', body: input }),
    deleteProduct: (priceId) => apiRequest(API_ROUTES.catalog.price(priceId), { method: 'DELETE' }),
  },
  factoryConfigs: {
    list: () => apiRequest(API_ROUTES.factoryConfigs.list),
    sendEmail: (ticketId, payload) => apiRequest(API_ROUTES.factoryConfigs.sendEmail(ticketId), { method: 'POST', body: payload }),
  },
  customers: {
    create: (payload) => apiRequest(API_ROUTES.customers.create, { method: 'POST', body: payload }),
    search: (q) => apiRequest(API_ROUTES.customers.search(q ?? '')),
    contacts: (customerId) => apiRequest(API_ROUTES.customers.contacts(customerId)),
    createContact: (customerId, payload) => apiRequest(API_ROUTES.customers.createContact(customerId), { method: 'POST', body: payload }),
    projects: (customerId) => apiRequest(API_ROUTES.customers.projects(customerId)),
    createProject: (customerId, payload) => apiRequest(API_ROUTES.customers.createProject(customerId), { method: 'POST', body: payload }),
  },
  fxRates: {
    list: () => apiRequest(API_ROUTES.fxRates.list),
    upsert: (currency, payload) => apiRequest(API_ROUTES.fxRates.upsert(currency), { method: 'PUT', body: payload }),
  },
  priceCalcConfigs: {
    list: () => apiRequest(API_ROUTES.priceCalcConfigs.list),
    update: (payload) => apiRequest(API_ROUTES.priceCalcConfigs.update, { method: 'POST', body: payload }),
  },
  attachments: {
    list: (ticketId) => apiRequest(API_ROUTES.attachments.list(ticketId)),
    upload: async (ticketId, file, attachType, quotationId) => {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('attachType', attachType || 'OTHER');
      if (quotationId) formData.append('quotationId', String(quotationId));
      const res = await fetch(API_ROUTES.attachments.upload(ticketId), {
        method: 'POST', credentials: 'include', body: formData,
      });
      if (!res.ok) { const err = await res.json().catch(() => ({})); throw new Error(err.message || 'Upload failed'); }
      return res.json();
    },
    fileUrl: (id) => API_ROUTES.attachments.file(id),
    delete: (id) => apiRequest(API_ROUTES.attachments.delete(id), { method: 'DELETE' }),
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
    create: async (payload) => {
      if (!Object.prototype.hasOwnProperty.call(payload, 'invoiceAttachment')) {
        return apiRequest(API_ROUTES.commissions.create, { method: 'POST', body: payload });
      }
      const formData = new FormData();
      if (payload.sourceTicketId) formData.append('sourceTicketId', String(payload.sourceTicketId));
      if (payload.salesRepId) formData.append('salesRepId', String(payload.salesRepId));
      formData.append('invoiceNumber', payload.invoiceNumber);
      formData.append('invoiceDate', payload.invoiceDate);
      formData.append('grossAmount', String(payload.grossAmount));
      formData.append('bankFees', String(payload.bankFees ?? 0));
      formData.append('suspenseVat', String(payload.suspenseVat ?? 0));
      formData.append('transportFee', String(payload.transportFee ?? 0));
      formData.append('cutFee', String(payload.cutFee ?? 0));
      formData.append('shortfall', String(payload.shortfall ?? 0));
      if (payload.invoiceAttachment) formData.append('invoiceAttachment', payload.invoiceAttachment);
      const res = await fetch(API_ROUTES.commissions.create, {
        method: 'POST',
        credentials: 'include',
        body: formData,
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || 'บันทึกค่าคอมไม่สำเร็จ');
      }
      return res.json();
    },
    updateDeductions: (id, payload) => apiRequest(API_ROUTES.commissions.deductions(id), { method: 'PATCH', body: payload }),
    approve: (id) => apiRequest(API_ROUTES.commissions.approve(id), { method: 'POST' }),
    reject: (id, payload = {}) => apiRequest(API_ROUTES.commissions.reject(id), { method: 'POST', body: payload }),
    clawback: (id, payload) => apiRequest(API_ROUTES.commissions.clawback(id), { method: 'POST', body: payload }),
    simulate: (payload) => apiRequest(API_ROUTES.commissions.simulator, { method: 'POST', body: payload }),
    payrollReady: (params) => apiRequest(withQuery(API_ROUTES.commissions.payrollReady, params)),
  },
  payroll: {
    current: (params) => apiRequest(withQuery(API_ROUTES.payroll.current, params)),
    preview: (payload) => apiRequest(API_ROUTES.payroll.preview, { method: 'POST', body: payload }),
    process: (payload) => apiRequest(API_ROUTES.payroll.process, { method: 'POST', body: payload }),
    bankExport: (periodId) => apiRequest(API_ROUTES.payroll.bankExport(periodId)),
    downloadPayslip: async (periodId, lineId) => {
      const res = await fetch(API_ROUTES.payroll.payslip(periodId, lineId), { credentials: 'include' });
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    },
    downloadOwnPayslip: async (periodId) => {
      const res = await fetch(API_ROUTES.payroll.ownPayslip(periodId), { credentials: 'include' });
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    },
    distributePayslips: (periodId) => apiRequest(API_ROUTES.payroll.distribute(periodId), { method: 'POST' }),
  },
  priceImport: {
    factories: () => apiRequest(API_ROUTES.priceImport.factories),
    createFactory: (name, country, defaultCurrency) => apiRequest(API_ROUTES.priceImport.factories, {
      method: 'POST',
      body: { name, country, defaultCurrency },
    }),
    versions: (factoryId) => apiRequest(API_ROUTES.priceImport.versions(factoryId)),
    upload: async (factoryId, file, label) => {
      const formData = new FormData();
      formData.append('factoryId', String(factoryId));
      formData.append('file', file);
      if (label) formData.append('label', label);
      const csrfToken = document.cookie.split('; ')
        .find((c) => c.startsWith('XSRF-TOKEN='))
        ?.split('=')[1];
      const res = await fetch(API_ROUTES.priceImport.upload, {
        method: 'POST',
        credentials: 'include',
        headers: csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {},
        body: formData,
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || 'อัปโหลดไม่สำเร็จ');
      }
      return res.json();
    },
    uploadAndCommit: async (factoryId, file, label) => {
      const formData = new FormData();
      formData.append('factoryId', String(factoryId));
      formData.append('file', file);
      if (label) formData.append('label', label);
      const csrfToken = document.cookie.split('; ')
        .find((c) => c.startsWith('XSRF-TOKEN='))
        ?.split('=')[1];
      const res = await fetch(API_ROUTES.priceImport.uploadCommit, {
        method: 'POST',
        credentials: 'include',
        headers: csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {},
        body: formData,
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || 'อัปโหลดไม่สำเร็จ');
      }
      return res.json();
    },
    validate: (versionId) => apiRequest(API_ROUTES.priceImport.validate(versionId), { method: 'POST' }),
    staging: (versionId) => apiRequest(API_ROUTES.priceImport.staging(versionId)),
    commit: (versionId) => apiRequest(API_ROUTES.priceImport.commit(versionId), { method: 'POST' }),
    getProfile: (factoryId) => apiRequest(API_ROUTES.priceImport.profile(factoryId)),
    updateProfile: (factoryId, json) => apiRequest(API_ROUTES.priceImport.profile(factoryId), {
      method: 'PUT',
      body: JSON.parse(json),
    }),
  },
};
