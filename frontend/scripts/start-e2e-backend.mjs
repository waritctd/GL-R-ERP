#!/usr/bin/env node
// Starts the Spring Boot backend configured for the real-backend e2e suite.
//
// Deliberately NOT wired into playwright.real.config.js's webServer: the backend needs a
// Postgres that this script cannot conjure, and burying that dependency inside a Playwright
// webServer entry turns "your database is down" into an opaque 120s timeout. Run this in its
// own terminal (or as a CI step) and let global-setup.js do the reachability check.
//
// Usage:
//   node scripts/start-e2e-backend.mjs
//
// Env overrides: E2E_REAL_FRONTEND_PORT, SERVER_PORT, PGHOST, PGPORT, PGDATABASE, PGUSER,
// PGPASSWORD, APP_UPLOADS_DIR.

import { spawn } from 'node:child_process';
import { connect } from 'node:net';
import { readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const BACKEND_DIR = join(REPO_ROOT, 'backend');
const TARGET_DIR = join(BACKEND_DIR, 'target');

const FRONTEND_PORT = process.env.E2E_REAL_FRONTEND_PORT || '5251';
const SERVER_PORT = process.env.SERVER_PORT || '8080';
const PGHOST = process.env.PGHOST || '127.0.0.1';
const PGPORT = process.env.PGPORT || '5432';
const PGDATABASE = process.env.PGDATABASE || 'hris';

function die(message) {
  console.error(`\n[e2e-backend] ${message}\n`);
  process.exit(1);
}

function findJar() {
  let entries;
  try {
    entries = readdirSync(TARGET_DIR);
  } catch {
    die(
      `No ${TARGET_DIR} directory — the backend has not been built.\n` +
        '  Run:  cd backend && ./mvnw -DskipTests package'
    );
  }
  // Maven leaves the pre-repackage jar as *.jar.original, and sources/javadoc jars can sit
  // alongside the boot jar; match only the executable artifact.
  const jars = entries.filter((name) => name.endsWith('.jar') && !name.includes('-sources'));
  if (jars.length === 0) {
    die(
      `No jar in ${TARGET_DIR}.\n` + '  Run:  cd backend && ./mvnw -DskipTests package'
    );
  }
  return join(TARGET_DIR, jars[0]);
}

function checkPostgres() {
  return new Promise((resolvePromise) => {
    const socket = connect({ host: PGHOST, port: Number(PGPORT) });
    const finish = (ok) => {
      socket.destroy();
      resolvePromise(ok);
    };
    socket.setTimeout(3000);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(false));
    socket.once('error', () => finish(false));
  });
}

const jar = findJar();

if (!(await checkPostgres())) {
  die(
    `Nothing listening on ${PGHOST}:${PGPORT} — the backend needs Postgres and will fail to start.\n` +
      '  Run:  docker compose up -d db\n' +
      '  ...or a local cluster:  pg_ctlcluster 16 main start && createdb hris'
  );
}

const env = {
  ...process.env,
  // The seed that creates the e2e logins. Without it the app starts perfectly and has no
  // demo accounts in it — see global-setup.js, which checks for exactly that.
  SPRING_PROFILES_ACTIVE: 'demo',
  SPRING_DATASOURCE_URL: process.env.SPRING_DATASOURCE_URL
    || `jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}`,
  SPRING_DATASOURCE_USERNAME: process.env.PGUSER || process.env.SPRING_DATASOURCE_USERNAME || 'postgres',
  SPRING_DATASOURCE_PASSWORD: process.env.PGPASSWORD || process.env.SPRING_DATASOURCE_PASSWORD || 'postgres',
  SERVER_PORT,
  // The suite runs over plain http; a Secure cookie would never be stored and every request
  // after login would arrive session-less.
  SERVER_SESSION_COOKIE_SECURE: 'false',
  // REQUIRED, and the least obvious thing in this file. The dev server proxies /api, but the
  // browser still sends its own Origin (http://127.0.0.1:<frontend port>) and Spring's CORS
  // filter rejects an unlisted one with a bare 403 "Invalid CORS request" — which looks
  // exactly like a failed login. The suite's port is not among application.yml's defaults
  // (5173/5174), so it has to be added here. The stock dev origins are kept so running this
  // does not break a `npm run dev` session pointed at the same backend.
  APP_CORS_ALLOWED_ORIGINS: [
    `http://127.0.0.1:${FRONTEND_PORT}`,
    `http://localhost:${FRONTEND_PORT}`,
    'http://127.0.0.1:5174',
    'http://localhost:5174',
  ].join(','),
  APP_UPLOADS_DIR: process.env.APP_UPLOADS_DIR || join(TARGET_DIR, 'e2e-uploads'),
};

console.log(`[e2e-backend] jar        ${jar}`);
console.log(`[e2e-backend] datasource ${env.SPRING_DATASOURCE_URL}`);
console.log(`[e2e-backend] port       ${SERVER_PORT}`);
console.log(`[e2e-backend] cors       ${env.APP_CORS_ALLOWED_ORIGINS}`);

const child = spawn('java', ['-jar', jar], { env, stdio: 'inherit' });

child.on('exit', (code, signal) => process.exit(signal ? 1 : (code ?? 0)));
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => child.kill(signal));
}
