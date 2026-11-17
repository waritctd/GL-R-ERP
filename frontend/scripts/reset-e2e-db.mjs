#!/usr/bin/env node
// Drops and recreates the e2e database so the next backend start re-runs Flyway from scratch, re-applying
// db/migration + db/migration-demo and restoring a pristine seed. CI never needs it — each job gets a
// brand-new Postgres service container.

import { spawnSync } from 'node:child_process';

const PGHOST = process.env.PGHOST || '127.0.0.1';
const PGPORT = process.env.PGPORT || '5432';
const PGUSER = process.env.PGUSER || 'postgres';
const PGPASSWORD = process.env.PGPASSWORD || 'postgres';
const PGDATABASE = process.env.PGDATABASE || 'hris';

function psql(sql, { database = 'postgres' } = {}) {
  const result = spawnSync(
    'psql',
    ['-h', PGHOST, '-p', PGPORT, '-U', PGUSER, '-d', database, '-v', 'ON_ERROR_STOP=1', '-tAc', sql],
    { env: { ...process.env, PGPASSWORD }, encoding: 'utf8' }
  );
  if (result.error && result.error.code === 'ENOENT') {
    console.error('\n[reset-e2e-db] psql is not on PATH — install the postgres client.\n');
    process.exit(1);
  }
  return result;
}

const probe = psql('SELECT 1');
if (probe.status !== 0) {
  console.error(
    `\n[reset-e2e-db] Cannot reach Postgres at ${PGHOST}:${PGPORT} as ${PGUSER}.\n` +
      `${(probe.stderr || '').trim()}\n`
  );
  process.exit(1);
}

// Fail early and clearly if the backend (or a psql session, or a GUI client) still holds the database open.
// DROP DATABASE would otherwise fail with a message that does not say what to do.
const connections = psql(
  `SELECT count(*) FROM pg_stat_activity WHERE datname = '${PGDATABASE}' AND pid <> pg_backend_pid()`
);
const openConnections = Number((connections.stdout || '0').trim());
if (openConnections > 0) {
  console.error(
    `\n[reset-e2e-db] ${openConnections} open connection(s) to "${PGDATABASE}" — refusing to drop it.\n` +
      '  Stop the backend first (the e2e backend is a plain `java -jar`, so Ctrl-C or kill it),\n' +
      '  then re-run this script.\n'
  );
  process.exit(1);
}

console.log(`[reset-e2e-db] dropping and recreating "${PGDATABASE}" on ${PGHOST}:${PGPORT}`);

const drop = psql(`DROP DATABASE IF EXISTS ${PGDATABASE}`);
if (drop.status !== 0) {
  console.error(`\n[reset-e2e-db] DROP failed:\n${(drop.stderr || '').trim()}\n`);
  process.exit(1);
}

const create = psql(`CREATE DATABASE ${PGDATABASE}`);
if (create.status !== 0) {
  console.error(`\n[reset-e2e-db] CREATE failed:\n${(create.stderr || '').trim()}\n`);
  process.exit(1);
}

console.log('[reset-e2e-db] done — empty database created.');
console.log('[reset-e2e-db] next: node scripts/start-e2e-backend.mjs (Flyway re-seeds on boot)');
