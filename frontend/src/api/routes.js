export const API_ROUTES = {
  auth: {
    login: '/api/auth/login',
    logout: '/api/auth/logout',
    me: '/api/auth/me',
  },
  employees: {
    list: '/api/employees',
    create: '/api/employees',
    detail: (id) => `/api/employees/${id}`,
  },
  profileRequests: {
    list: '/api/profile-requests',
    create: '/api/profile-requests',
    detail: (id) => `/api/profile-requests/${id}`,
  },
  tickets: {
    list: '/api/tickets',
    create: '/api/tickets',
    detail: (id) => `/api/tickets/${id}`,
    action: (id, action) => `/api/tickets/${id}/${action}`,
    editItems: (id) => `/api/tickets/${id}/items`,
  },
  dashboard: {
    summary: '/api/dashboard/summary',
  },
  notifications: {
    list: '/api/notifications',
    markRead: (id) => `/api/notifications/${id}/read`,
  },
};

export const ROLE_PERMISSIONS = {
  canUseEmployeeExperience: ['employee'],
  canSubmitProfileRequests: ['employee'],
  canViewEmployees: ['hr'],
  canManageEmployees: ['hr'],
  canReviewProfileRequests: ['hr'],
  // Sales module
  canViewTickets: ['sales', 'import', 'ceo', 'admin'],
  canCreateTickets: ['sales', 'admin'],
  canPickupTickets: ['import', 'admin'],
  canProposePrices: ['import', 'admin'],
  canApproveReject: ['ceo', 'admin'],
  canGenerateQuotation: ['sales', 'admin'],
};
