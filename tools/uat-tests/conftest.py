"""pytest fixtures + the UAT-ID report writer.

- `base_url` comes from UAT_BASE_URL (default the local uat stack on :8099).
- One fixture per seeded persona, each logging in with the fresh-seed temp password `Uat@2026`.
- Every test carries `@pytest.mark.uat("<ID>", title=..., priority="P0"|"P1")`; the makereport hook
  collects results and sessionfinish writes reports/uat-results.{md,csv}. `--junitxml` handles junit.
"""
import csv
import datetime
import os
import pathlib
import time

import pytest
import requests

from glrclient import GlrClient

PASSWORD = os.environ.get("UAT_PASSWORD", "Uat@2026")
MAILPIT_URL = os.environ.get("MAILPIT_URL", "http://localhost:8025")
_RESULTS = []

# Opt-in per-request throttle (UAT_THROTTLE=<seconds>) to stay under the hosted-UAT
# rate limiter when running the full suite against live UAT. TEMPORARY / local-only.
if os.environ.get("UAT_THROTTLE"):
    _throttle_delay = float(os.environ["UAT_THROTTLE"])
    _orig_request = requests.sessions.Session.request

    def _throttled_request(self, *args, **kwargs):
        time.sleep(_throttle_delay)
        return _orig_request(self, *args, **kwargs)

    requests.sessions.Session.request = _throttled_request


@pytest.fixture(scope="session")
def base_url():
    return os.environ.get("UAT_BASE_URL", "http://localhost:8099")


def _persona(base_url, email):
    c = GlrClient(base_url)
    c.login(email, PASSWORD)
    return c


@pytest.fixture
def employee(base_url):
    return _persona(base_url, "employee@uat.glr")


@pytest.fixture
def hr(base_url):
    return _persona(base_url, "hr@uat.glr")


@pytest.fixture
def ceo(base_url):
    return _persona(base_url, "ceo@uat.glr")


@pytest.fixture
def sales(base_url):
    return _persona(base_url, "sales@uat.glr")


@pytest.fixture
def salesmgr(base_url):
    return _persona(base_url, "salesmgr@uat.glr")


@pytest.fixture
def account(base_url):
    return _persona(base_url, "account@uat.glr")


@pytest.fixture
def import_(base_url):
    return _persona(base_url, "import@uat.glr")


@pytest.fixture
def divmgr(base_url):
    return _persona(base_url, "divmgr@uat.glr")


@pytest.fixture
def nulldiv(base_url):
    return _persona(base_url, "nulldiv@uat.glr")


@pytest.fixture
def admin(base_url):
    return _persona(base_url, "admin@uat.glr")


def pytest_configure(config):
    config.addinivalue_line(
        "markers", "uat(id, title='', priority=''): UAT case tag for the report"
    )
    config.addinivalue_line(
        "markers",
        "live_email: fires a real email via Resend (./run.sh --live-email only, excluded by default)",
    )


def _mailpit_reachable():
    """MAIL-* tests assert on real captured email via Mailpit's REST API. Mailpit only exists in the
    docker-compose.uat.yml stack; running the harness against a bare `java -jar` backend (no Docker)
    has no Mailpit to poll, so those tests would otherwise fail with a raw ConnectionError instead of
    a clear skip. Checked once at collection time, not per-test."""
    try:
        r = requests.get(f"{MAILPIT_URL}/api/v1/info", timeout=2)
        return r.ok
    except requests.RequestException:
        return False


def pytest_collection_modifyitems(config, items):
    if _mailpit_reachable():
        return
    skip_mail = pytest.mark.skip(
        reason=f"Mailpit unavailable at {MAILPIT_URL} — needs Docker stack (docker-compose.uat.yml)"
    )
    for item in items:
        m = item.get_closest_marker("uat")
        if m and m.args and str(m.args[0]).startswith("MAIL-"):
            item.add_marker(skip_mail)


@pytest.fixture(autouse=True)
def _pace_live_email(request):
    """Spread live-email tests over time so the real Resend sends stay under its 10 req/s cap.

    Each `@pytest.mark.live_email` test fires 1-3 async notification emails via the real Resend API
    (./run.sh --live-email only). Running all ~21 of them back-to-back bursts ~76 emails in a few
    seconds and gets many HTTP 429s. Sleeping after each live test spreads the ~76 emails over
    ~30-40s (~2/s) instead. Does not affect the default Mailpit suite (no marker -> no sleep).
    """
    yield
    if request.node.get_closest_marker("live_email") is not None:
        time.sleep(1.5)


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    rep = (yield).get_result()
    m = item.get_closest_marker("uat")
    if m is None:
        return
    # Record once per test: the 'call' phase, or 'setup' if the test was skipped there.
    if rep.when == "call" or (rep.when == "setup" and rep.skipped):
        if rep.passed:
            status, detail = "PASS", ""
        elif rep.skipped:
            status = "SKIP"
            detail = next((l for l in reversed(str(rep.longrepr).splitlines()) if l.strip()), "")[:300]
        else:
            status = "FAIL"
            detail = next((l for l in reversed(str(rep.longrepr).splitlines()) if l.strip()), "")[:300]
        _RESULTS.append(
            {
                "id": m.args[0] if m.args else item.nodeid,
                "title": m.kwargs.get("title", ""),
                "priority": m.kwargs.get("priority", ""),
                "result": status,
                "detail": detail,
            }
        )


def pytest_sessionfinish(session, exitstatus):
    if not _RESULTS:
        return
    out = pathlib.Path(__file__).parent / "reports"
    out.mkdir(exist_ok=True)
    rows = sorted(_RESULTS, key=lambda r: r["id"])
    p0 = [r for r in rows if r["priority"] == "P0"]
    p0p = sum(r["result"] == "PASS" for r in p0)
    ts = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
    md = [
        f"# UAT results ({ts})",
        "",
        f"- Total {len(rows)} | Pass {sum(r['result'] == 'PASS' for r in rows)} | "
        f"Fail {sum(r['result'] == 'FAIL' for r in rows)} | Skip {sum(r['result'] == 'SKIP' for r in rows)}",
        f"- **P0 go-live gate: {p0p}/{len(p0)} passed**",
        "",
        "| UAT-ID | Title | Priority | Result | Detail |",
        "|---|---|---|---|---|",
    ]
    for r in rows:
        detail = r["detail"].replace("|", "/")
        md.append(f"| {r['id']} | {r['title']} | {r['priority']} | {r['result']} | {detail} |")
    (out / "uat-results.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    with (out / "uat-results.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["id", "title", "priority", "result", "detail"])
        w.writeheader()
        w.writerows(rows)
    print(f"\nUAT report -> {out / 'uat-results.md'} (P0 {p0p}/{len(p0)})")
