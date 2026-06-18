# GL&R HR React Frontend

React/Vite implementation of the phase 1 HR portal.

## Run With Mock Data

```bash
cd frontend
npm install
npm run dev
```

## Run Against Spring Boot

Start the backend first, then:

```bash
cd frontend
VITE_USE_MOCKS=false VITE_API_BASE_URL=http://127.0.0.1:8080 npm run dev
```

Use the same hostname for both apps, such as `127.0.0.1`, so the session cookie is sent consistently.

API wrapper lives in `src/api/` and maps to:

- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/me`
- `GET /api/employees`
- `POST /api/employees`
- `GET /api/employees/[id]`
- `PATCH /api/employees/[id]`
- `GET /api/profile-requests`
- `POST /api/profile-requests`
- `PATCH /api/profile-requests/[id]`
- `GET /api/users`
- `POST /api/users`
- `PATCH /api/users/[id]`
