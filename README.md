# GL&R HR React Frontend

React/Vite implementation of the phase 1 HR portal.

## Run

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 5174
```

## Spring Boot API Switch

The app uses mock data by default. To call the Spring Boot backend instead:

```bash
VITE_USE_MOCKS=false
VITE_API_BASE_URL=http://localhost:8080
```

API wrapper lives in `src/api/` and maps to the requested routes:

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
