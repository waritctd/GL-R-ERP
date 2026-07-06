# GL&R HR React Frontend

React/Vite implementation of the phase 1 HR portal.

## Run Locally

```bash
cd frontend
npm install
npm run dev
```

## Run Against Spring Boot

Start the backend first, then:

```bash
cd frontend
VITE_API_BASE_URL=http://127.0.0.1:8080 npm run dev
```

Use the same hostname for both apps, such as `127.0.0.1`, so the session cookie is sent consistently.

## Environment Variables

| Variable | Default | Notes |
| --- | --- | --- |
| `VITE_API_BASE_URL` | empty string | API origin. Use `http://127.0.0.1:8080` for a local backend, or leave empty when using a same-origin proxy. |
| `VITE_USE_MOCKS` | `false` | `true` serves the in-browser mock API (`src/api/mockApi.js`) instead of the real backend. Never set `true` in a production build — `api/index.js` throws at load time if it detects `PROD` with mocks enabled. |
| `VITE_DEMO_LOGIN` | `false` | Currently unreferenced in application code; reserved. |

## Build And Checks

```bash
cd frontend
npm run build
npm run lint
npm test
```

`npm test` runs the Vitest suite; `npm run lint` runs ESLint (incl. `jsx-a11y`). Both are enforced in CI.

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
