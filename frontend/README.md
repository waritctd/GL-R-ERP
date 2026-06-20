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

## Environment Variables

| Variable | Default | Notes |
| --- | --- | --- |
| `VITE_USE_MOCKS` | Development uses mocks unless set to `false` | Set `false` to call the Spring Boot API. Set `true` for local/demo mock mode. |
| `VITE_API_BASE_URL` | empty string | API origin. Use `http://127.0.0.1:8080` for a local backend, or leave empty when using a same-origin proxy. |
| `VITE_DEMO_LOGIN` | `false` in production builds | Set `true` only for demo deployments that should show quick-login buttons and demo credential defaults. |

## Build And Checks

```bash
cd frontend
npm run build
```

There are no frontend `lint` or `test` scripts yet. CI can safely call `npm run lint --if-present` and `npm test --if-present` until those are added.

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
