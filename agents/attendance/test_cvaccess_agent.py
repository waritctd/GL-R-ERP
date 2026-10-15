"""Behaviour tests for cvaccess_agent.py, run against a real local PostgreSQL.

The acc_transaction table below is our reading of ZKBio CVAccess's schema, not a
copy of it; `cvaccess_agent.py --check` verifies the real one on site.

    python3 -m unittest discover -s agents/attendance -p 'test_cvaccess_agent.py' -v

Connects to 127.0.0.1:5432 as $CVACCESS_TEST_PG_USER (default: the OS user) and
creates, then drops, the database glr_cvaccess_agent_test.
"""

from __future__ import annotations

import contextlib
import getpass
import io
import json
import os
import tempfile
import threading
import unittest
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

import pg8000.dbapi

import cvaccess_agent

PG = {
    "host": os.getenv("CVACCESS_TEST_PG_HOST", "127.0.0.1"),
    "port": int(os.getenv("CVACCESS_TEST_PG_PORT", "5432")),
    "user": os.getenv("CVACCESS_TEST_PG_USER", getpass.getuser()),
    "password": os.getenv("CVACCESS_TEST_PG_PASSWORD") or None,
}
TEST_DB = "glr_cvaccess_agent_test"


class Sink(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.server.attempts.append(body)
        if self.server.status < 300:
            self.server.received.append((body, self.headers.get("X-GLR-Agent-Token")))
        self.send_response(self.server.status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *args) -> None:
        pass


def ts(value: str) -> datetime:
    return datetime.fromisoformat(value)


def admin_execute(sql: str) -> None:
    admin = pg8000.dbapi.connect(database="postgres", **PG)
    admin.autocommit = True
    try:
        admin.cursor().execute(sql)
    finally:
        admin.close()


class CvaccessAgentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        admin_execute(f"DROP DATABASE IF EXISTS {TEST_DB} WITH (FORCE)")
        admin_execute(f"CREATE DATABASE {TEST_DB}")
        cls.db = pg8000.dbapi.connect(database=TEST_DB, **PG)
        cls.db.autocommit = True
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Sink)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.db.close()
        admin_execute(f"DROP DATABASE IF EXISTS {TEST_DB} WITH (FORCE)")

    def setUp(self) -> None:
        cur = self.db.cursor()
        cur.execute("DROP TABLE IF EXISTS acc_transaction")
        cur.execute(
            """CREATE TABLE acc_transaction (
                   id          varchar(50) PRIMARY KEY,
                   create_time timestamp NOT NULL,
                   event_time  timestamp,
                   pin         varchar(30),
                   event_no    smallint,
                   event_name  varchar(100))"""
        )
        self.server.status = 200
        self.server.attempts = []
        self.server.received = []

        data_dir = tempfile.TemporaryDirectory()
        self.addCleanup(data_dir.cleanup)
        env = {
            "CVACCESS_DB_HOST": PG["host"],
            "CVACCESS_DB_PORT": str(PG["port"]),
            "CVACCESS_DB_NAME": TEST_DB,
            "CVACCESS_DB_USER": PG["user"],
            "CVACCESS_DB_PASSWORD": PG["password"] or "",
            "ATTENDANCE_API_URL": f"http://127.0.0.1:{self.server.server_port}/api/attendance/punch",
            "ATTENDANCE_AGENT_TOKEN": "test-token",
            "ATTENDANCE_AGENT_DATA_DIR": data_dir.name,
            "ATTENDANCE_SITE_CODE": "SHOWROOM",
            "ATTENDANCE_DEVICE_CODE": "SHOWROOM_SC700",
            "ATTENDANCE_TIMEZONE": "Asia/Bangkok",
            "ATTENDANCE_POST_TIMEOUT_SECONDS": "2",
        }
        patcher = mock.patch.dict(os.environ, env)
        patcher.start()
        self.addCleanup(patcher.stop)
        for leftover in ("ATTENDANCE_STATE_FILE", "ATTENDANCE_QUEUE_FILE",
                         "ATTENDANCE_DRY_RUN", "CVACCESS_PUNCH_EVENTS"):
            os.environ.pop(leftover, None)

    def insert(self, row_id: str, create_time: str | None, event_time: str, pin: str | None,
               event_no: int = 0, event_name: str = "Normal Verify Open") -> None:
        self.db.cursor().execute(
            "INSERT INTO acc_transaction VALUES (%s, %s, %s, %s, %s, %s)",
            (row_id, ts(create_time) if create_time else None, ts(event_time), pin, event_no, event_name),
        )

    def agent(self, *argv: str) -> int:
        return cvaccess_agent.main(list(argv))

    def delivered(self) -> list[tuple[str, str]]:
        return [(body["badge_code"], body["punch_time"]) for body, _ in self.server.received]

    def seed_three_september_punches(self) -> None:
        self.insert("a", "2026-09-06 17:59:01", "2026-09-06 17:59:00", "10021")
        self.insert("b", "2026-09-08 08:01:02", "2026-09-08 08:01:00", "10021")
        self.insert("c", "2026-09-08 08:05:02", "2026-09-08 08:05:00", "10035")
        self.insert("d", "2026-09-08 17:31:02", "2026-09-08 17:31:00", "10021")

    def test_backfill_delivers_every_punch_since_the_date(self) -> None:
        self.seed_three_september_punches()

        self.assertEqual(self.agent("--once", "--since", "2026-09-07"), 0)

        self.assertEqual(self.delivered(), [
            ("10021", "2026-09-08T08:01:00+07:00"),
            ("10035", "2026-09-08T08:05:00+07:00"),
            ("10021", "2026-09-08T17:31:00+07:00"),
        ])
        body, token = self.server.received[0]
        self.assertEqual(token, "test-token")
        self.assertEqual(
            {k: body[k] for k in ("site_code", "device_code", "work_date", "punch_source", "ingest_method")},
            {"site_code": "SHOWROOM", "device_code": "SHOWROOM_SC700", "work_date": "2026-09-08",
             "punch_source": "BIOMETRIC", "ingest_method": "CATCHUP_PULL"},
        )

    def test_punch_just_after_midnight_stays_on_its_local_day(self) -> None:
        self.insert("a", "2026-09-08 00:30:02", "2026-09-08 00:30:00", "10021")

        self.agent("--once", "--since", "2026-09-07")

        self.assertEqual([(body["punch_time"], body["work_date"]) for body, _ in self.server.received],
                         [("2026-09-08T00:30:00+07:00", "2026-09-08")])

    def test_rows_that_are_not_employee_punches_are_skipped(self) -> None:
        self.insert("a", "2026-09-08 08:00:01", "2026-09-08 08:00:00", "")
        self.insert("b", "2026-09-08 08:00:02", "2026-09-08 08:00:01", None)
        self.insert("c", "2026-09-08 08:00:03", "2026-09-08 08:00:02", "admin")
        self.insert("d", "2026-09-08 08:00:04", "2026-09-08 08:00:03", "10021", 23, "Access Denied")
        self.insert("e", "2026-09-08 08:00:05", "2026-09-08 08:00:04", "10035")

        self.agent("--once", "--since", "2026-09-07")

        self.assertEqual(self.delivered(), [("10035", "2026-09-08T08:00:04+07:00")])

    def test_punch_event_numbers_are_configurable(self) -> None:
        os.environ["CVACCESS_PUNCH_EVENTS"] = "0,15"
        self.insert("a", "2026-09-08 08:00:01", "2026-09-08 08:00:00", "10021", 15, "Face Verify Open")
        self.insert("b", "2026-09-08 08:00:02", "2026-09-08 08:00:01", "10035", 23, "Access Denied")
        self.insert("c", "2026-09-08 08:00:03", "2026-09-08 08:00:02", "10042")

        self.agent("--once", "--since", "2026-09-07")

        self.assertEqual([badge for badge, _ in self.delivered()], ["10021", "10042"])

    def test_a_second_run_does_not_redeliver(self) -> None:
        self.seed_three_september_punches()
        self.agent("--once", "--since", "2026-09-07")
        self.assertEqual(len(self.server.attempts), 3)
        self.server.attempts.clear()

        self.agent("--once")

        self.assertEqual(self.server.attempts, [])

    def test_a_late_arriving_older_punch_is_still_delivered(self) -> None:
        self.seed_three_september_punches()
        self.agent("--once", "--since", "2026-09-07")
        self.server.received.clear()
        # The device buffered this 07:50 punch while offline and CVAccess stored it
        # the next day, after the 17:31 punch had already been delivered.
        self.insert("e", "2026-09-09 10:00:00", "2026-09-08 07:50:00", "10042")

        self.agent("--once")

        self.assertEqual(self.delivered(), [("10042", "2026-09-08T07:50:00+07:00")])

    def test_rows_sharing_one_insert_time_survive_paging(self) -> None:
        for i in range(5):
            self.insert(f"r{i}", "2026-09-08 08:00:00", f"2026-09-08 08:00:0{i}", f"1002{i}")

        with mock.patch.object(cvaccess_agent, "PAGE_SIZE", 2):
            self.agent("--once", "--since", "2026-09-07")

        self.assertEqual(sorted(badge for badge, _ in self.delivered()),
                         ["10020", "10021", "10022", "10023", "10024"])

    def test_first_run_without_since_skips_history_then_follows_new_rows(self) -> None:
        self.seed_three_september_punches()

        self.agent("--once")
        self.assertEqual(self.server.attempts, [])

        self.insert("e", "2026-10-15 08:00:02", "2026-10-15 08:00:00", "10042")
        self.agent("--once")

        self.assertEqual(self.delivered(), [("10042", "2026-10-15T08:00:00+07:00")])

    def test_a_backend_outage_loses_and_duplicates_nothing(self) -> None:
        self.seed_three_september_punches()
        self.server.status = 500
        self.agent("--once", "--since", "2026-09-07")
        self.assertEqual(self.server.received, [])

        self.server.status = 200
        self.agent("--once")

        self.assertEqual(self.delivered(), [
            ("10021", "2026-09-08T08:01:00+07:00"),
            ("10035", "2026-09-08T08:05:00+07:00"),
            ("10021", "2026-09-08T17:31:00+07:00"),
        ])

    def test_dry_run_posts_nothing_and_does_not_move_the_cursor(self) -> None:
        self.insert("a", "2026-09-08 08:01:02", "2026-09-08 08:01:00", "10021")
        self.agent("--once", "--since", "2026-09-07")
        self.insert("b", "2026-09-08 08:05:02", "2026-09-08 08:05:00", "10035")
        self.server.attempts.clear()
        self.server.received.clear()

        self.agent("--once", "--dry-run")
        self.assertEqual(self.server.attempts, [])

        self.agent("--once")
        self.assertEqual(self.delivered(), [("10035", "2026-09-08T08:05:00+07:00")])

    def test_check_passes_and_reports_which_events_occur(self) -> None:
        self.insert("a", "2026-09-08 08:01:02", "2026-09-08 08:01:00", "10021")
        self.insert("b", "2026-09-08 08:02:02", "2026-09-08 08:02:00", "10035", 15, "Face Verify Open")
        out = io.StringIO()

        with contextlib.redirect_stdout(out):
            code = self.agent("--check")

        self.assertEqual(code, 0)
        self.assertIn("Normal Verify Open", out.getvalue())
        self.assertIn("Face Verify Open", out.getvalue())
        self.assertIn("timestamp without time zone", out.getvalue())

    def test_a_uuid_id_column_round_trips_through_the_saved_cursor(self) -> None:
        self.db.cursor().execute("ALTER TABLE acc_transaction ALTER COLUMN id TYPE uuid USING id::uuid")
        self.insert("00000000-0000-0000-0000-00000000000b", "2026-09-08 08:01:02", "2026-09-08 08:01:00", "10021")
        self.insert("00000000-0000-0000-0000-00000000000c", "2026-09-08 08:05:02", "2026-09-08 08:05:00", "10035")

        self.assertEqual(self.agent("--once", "--since", "2026-09-07"), 0)
        self.assertEqual(len(self.delivered()), 2)
        self.server.attempts.clear()
        self.agent("--once")

        self.assertEqual(self.server.attempts, [])

    def test_a_row_without_an_insert_time_does_not_stop_the_agent(self) -> None:
        self.db.cursor().execute("ALTER TABLE acc_transaction ALTER COLUMN create_time DROP NOT NULL")
        self.seed_three_september_punches()
        self.insert("n", None, "2026-09-08 09:00:00", "10042")

        self.assertEqual(self.agent("--once"), 0)
        self.insert("e", "2026-10-15 08:00:02", "2026-10-15 08:00:00", "10050")
        self.assertEqual(self.agent("--once"), 0)

        self.assertEqual(self.delivered(), [("10050", "2026-10-15T08:00:00+07:00")])

    def test_since_is_refused_outside_a_one_off_run(self) -> None:
        # A service started with --since would re-send all history on every restart.
        self.seed_three_september_punches()
        err = io.StringIO()

        with mock.patch.object(cvaccess_agent.time, "sleep", side_effect=KeyboardInterrupt), \
                contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as raised:
            self.agent("--since", "2026-09-07")

        self.assertEqual(raised.exception.code, 2)
        self.assertIn("--since needs --once", err.getvalue())
        self.assertEqual(self.server.attempts, [])

    def test_a_backfill_skips_rows_without_an_insert_time(self) -> None:
        self.db.cursor().execute("ALTER TABLE acc_transaction ALTER COLUMN create_time DROP NOT NULL")
        self.seed_three_september_punches()
        self.insert("n", None, "2026-09-08 09:00:00", "10042")

        self.assertEqual(self.agent("--once", "--since", "2026-09-07"), 0)

        self.assertNotIn("10042", [badge for badge, _ in self.delivered()])

    def test_a_one_day_resweep_does_not_move_the_service_cursor(self) -> None:
        self.insert("a", "2026-10-10 08:00:02", "2026-10-10 08:00:00", "10021")
        self.agent("--once", "--since", "2026-10-10")
        # Punched on the 11th but stored on the 14th, just before the 14th's own punch;
        # the service has not polled since.
        self.insert("x", "2026-10-14 07:00:00", "2026-10-11 07:50:00", "10042")
        self.insert("b", "2026-10-14 08:00:02", "2026-10-14 08:00:00", "10035")
        self.agent("--once", "--since", "2026-10-14")
        self.server.received.clear()

        self.agent("--once")

        self.assertIn(("10042", "2026-10-11T07:50:00+07:00"), self.delivered())

    def test_the_live_loop_reconnects_after_a_database_restart(self) -> None:
        self.seed_three_september_punches()
        self.agent("--once")
        sleeps: list[float] = []

        def fake_sleep(seconds: float) -> None:
            sleeps.append(seconds)
            if len(sleeps) == 1:
                self.db.cursor().execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    "WHERE datname = %s AND pid <> pg_backend_pid()", (TEST_DB,))
                self.insert("e", "2026-10-15 08:00:02", "2026-10-15 08:00:00", "10042")
            elif self.server.received or len(sleeps) > 10:
                raise KeyboardInterrupt

        with mock.patch.object(cvaccess_agent.time, "sleep", side_effect=fake_sleep):
            try:
                code = self.agent()
            except Exception as exc:
                self.fail(f"the agent died instead of reconnecting: {exc!r}")

        self.assertEqual(code, 0)
        self.assertEqual(self.delivered(), [("10042", "2026-10-15T08:00:00+07:00")])

    def test_check_lists_only_rows_that_will_be_sent_as_newest(self) -> None:
        self.db.cursor().execute("ALTER TABLE acc_transaction ALTER COLUMN create_time DROP NOT NULL")
        self.insert("a", "2026-09-08 08:01:02", "2026-09-08 08:01:00", "10021")
        self.insert("n", None, "2026-09-08 09:00:00", "10042")
        out = io.StringIO()

        with contextlib.redirect_stdout(out):
            self.agent("--check")

        self.assertNotIn("10042", out.getvalue())

    def test_the_agent_session_cannot_write_or_hold_a_transaction_open(self) -> None:
        conn = cvaccess_agent.connect(cvaccess_agent.db_settings())
        self.addCleanup(conn.close)
        cur = conn.cursor()
        cur.execute("SELECT pg_backend_pid()")
        pid = cur.fetchone()[0]
        observer = self.db.cursor()
        observer.execute("SELECT state FROM pg_stat_activity WHERE pid = %s", (pid,))
        self.assertEqual(observer.fetchone()[0], "idle")

        with self.assertRaises(pg8000.dbapi.DatabaseError) as raised:
            cur.execute("INSERT INTO acc_transaction (id, create_time) VALUES ('x', now())")

        self.assertEqual(raised.exception.args[0]["C"], "25006")

    def test_check_fails_and_names_a_missing_column(self) -> None:
        self.db.cursor().execute("ALTER TABLE acc_transaction DROP COLUMN create_time")
        out = io.StringIO()

        with contextlib.redirect_stdout(out):
            code = self.agent("--check")

        self.assertNotEqual(code, 0)
        self.assertIn("create_time", out.getvalue())


if __name__ == "__main__":
    unittest.main()
