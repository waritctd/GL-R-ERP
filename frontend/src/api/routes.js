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
  users: {
    list: '/api/users',
    create: '/api/users',
    detail: (id) => `/api/users/${id}`,
  },
};

export const ROLE_PERMISSIONS = {
  canViewEmployees: ['hr', 'director', 'admin'],
  canManageEmployees: ['hr', 'admin'],
  canReviewProfileRequests: ['hr'],
  canManageUsers: ['admin'],
};
