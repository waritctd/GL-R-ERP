# GL&R HR Portal

This repository is split into two clear applications:

```text
frontend/  React + Vite HR portal
backend/   Spring Boot API for PostgreSQL/Supabase
```

The frontend can still run with mock data, or it can call the Spring Boot backend through the existing `/api/*` contract.

## Frontend

```bash
cd frontend
npm install
npm run dev
```

To use the backend instead of mocks:

```bash
cd frontend
VITE_USE_MOCKS=false VITE_API_BASE_URL=http://127.0.0.1:8080 npm run dev
```

See [frontend/README.md](frontend/README.md).

## Backend

The backend uses Spring Boot, Flyway, and PostgreSQL. Your Supabase demo connection is stored locally in ignored file `backend/.env.local`; commit only `backend/.env.example`.

```bash
cd backend
set -a
source .env.local
set +a
mvn spring-boot:run
```

See [backend/README.md](backend/README.md).

## Vercel Frontend Deployment

This repo includes a root-level `vercel.json` for Vercel projects whose Root Directory is the repository root. It installs and builds `frontend/`, publishes `frontend/dist`, proxies `/api/*` to the Render backend, and serves `index.html` for React SPA routes.

If the Vercel project Root Directory is set to `frontend`, the matching `frontend/vercel.json` can be used instead.

## Production Shape

For on-prem production, use the same backend and schema migrations with a different PostgreSQL datasource. Keep demo seeding and quick role login disabled:

```bash
APP_DEMO_SEED_ENABLED=false
APP_QUICK_ROLE_LOGIN_ENABLED=false
```
