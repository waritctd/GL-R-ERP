# GL&R HR Portal

This repository is split into two clear applications:

```text
frontend/  React + Vite HR portal
backend/   Spring Boot API for PostgreSQL/Supabase
```

The frontend calls the Spring Boot backend through the existing `/api/*` contract.

## Frontend

```bash
cd frontend
npm install
npm run dev
```

To point the frontend at a local backend:

```bash
cd frontend
VITE_API_BASE_URL=http://127.0.0.1:8080 npm run dev
```

See [frontend/README.md](frontend/README.md).

## Backend

The backend uses Spring Boot, Flyway, and PostgreSQL/Supabase. Local database secrets belong in ignored file `backend/.env.local`; commit only `backend/.env.example`.

```bash
cd backend
set -a
source .env.local
set +a
./mvnw spring-boot:run
```

See [backend/README.md](backend/README.md).

## Tests And Builds

Run the backend unit/controller tests:

```bash
cd backend
./mvnw test
```

15 integration tests are skipped unless `TEST_DB_URL`, `TEST_DB_USERNAME`, and `TEST_DB_PASSWORD` point at a real Postgres. `./mvnw test` passes without them but only runs the Mockito unit tests; set them to also exercise the Flyway/repository integration suite (`AbstractPostgresIntegrationTest`).

Build the backend package:

```bash
cd backend
./mvnw -DskipTests package
```

Build the frontend:

```bash
cd frontend
npm run build
```

Run the frontend lint and test scripts:

```bash
cd frontend
npm run lint
npm test
```

`npm test` runs the Vitest suite.

## Vercel Frontend Deployment

This repo includes a root-level `vercel.json` for Vercel projects whose Root Directory is the repository root. It installs and builds `frontend/`, publishes `frontend/dist`, proxies `/api/*` to the Render backend, and serves `index.html` for React SPA routes.

If the Vercel project Root Directory is set to `frontend`, the matching `frontend/vercel.json` can be used instead.

## Production Shape

For production/on-prem, use the same backend and schema migrations with the target PostgreSQL datasource. Authentication uses employee email from `hr.employee`; the temporary password is the employee code.
