"""Unit tests for the attendance agent's multi-target (dual-post) delivery.

These exercise only the config + delivery/queue/state layer, which is pure Python
and needs no device: ``requests.post`` is monkeypatched. Run with:

    pip install requests pytest tzdata
    pytest agents/attendance/test_showroom_agent.py -q
"""
from __future__ import annotations

import json

import pytest

import showroom_agent as agent


class FakeResponse:
    def __init__(self, status_code: int = 200, text: str = "ok") -> None:
        self.status_code = status_code
        self.text = text


def _clear_env(monkeypatch) -> None:
    for var in (
        "ATTENDANCE_API_TARGETS", "ATTENDANCE_API_URL", "ATTENDANCE_AGENT_TOKEN",
        "ATTENDANCE_STATE_FILE", "ATTENDANCE_QUEUE_FILE", "ATTENDANCE_DRY_RUN",
    ):
        monkeypatch.delenv(var, raising=False)


def make_config(monkeypatch, tmp_path, *, targets_json=None, single_url=None, dry_run=False):
    _clear_env(monkeypatch)
    monkeypatch.setenv("ATTENDANCE_AGENT_DATA_DIR", str(tmp_path))
    if dry_run:
        monkeypatch.setenv("ATTENDANCE_DRY_RUN", "true")
    if targets_json is not None:
        monkeypatch.setenv("ATTENDANCE_API_TARGETS", targets_json)
    if single_url is not None:
        monkeypatch.setenv("ATTENDANCE_API_URL", single_url)
    return agent.AgentConfig.from_env()


def two_targets():
    return json.dumps([
        {"name": "prod", "url": "https://prod.example/api/attendance/punch", "token": "ptok"},
        {"name": "uat", "url": "https://uat.example/api/attendance/punch", "token": "utok"},
    ])


def sample_payload(badge="1001", when="2026-07-24T08:40:00+07:00"):
    return {
        "badge_code": badge, "punch_time": when, "work_date": "2026-07-24",
        "site_code": "SHOWROOM", "device_code": "SHOWROOM_SC700",
    }


# --------------------------------------------------------------------------- #
# Config / target resolution
# --------------------------------------------------------------------------- #
def test_single_target_backward_compat(monkeypatch, tmp_path):
    cfg = make_config(monkeypatch, tmp_path, single_url="http://x/api/attendance/punch")
    assert len(cfg.targets) == 1
    t = cfg.targets[0]
    assert t.name == "default"
    assert t.url == "http://x/api/attendance/punch"
    # original filenames preserved so existing deployments don't reset their watermark
    assert t.state_file.name == "showroom_agent_state.json"
    assert t.queue_file.name == "showroom_agent_queue.jsonl"


def test_multi_target_parse(monkeypatch, tmp_path):
    cfg = make_config(monkeypatch, tmp_path, targets_json=two_targets())
    assert [t.name for t in cfg.targets] == ["prod", "uat"]
    assert cfg.targets[0].token == "ptok"
    assert cfg.targets[1].url == "https://uat.example/api/attendance/punch"
    # per-target isolated state/queue files
    assert cfg.targets[0].state_file.name == "agent_state.prod.json"
    assert cfg.targets[1].queue_file.name == "agent_queue.uat.jsonl"


def test_invalid_targets_json_exits(monkeypatch, tmp_path):
    with pytest.raises(SystemExit):
        make_config(monkeypatch, tmp_path, targets_json="not-json")


def test_duplicate_target_name_exits(monkeypatch, tmp_path):
    dup = json.dumps([
        {"name": "prod", "url": "https://a/p"},
        {"name": "prod", "url": "https://b/p"},
    ])
    with pytest.raises(SystemExit):
        make_config(monkeypatch, tmp_path, targets_json=dup)


# --------------------------------------------------------------------------- #
# Fan-out delivery
# --------------------------------------------------------------------------- #
def test_deliver_posts_to_every_target(monkeypatch, tmp_path):
    cfg = make_config(monkeypatch, tmp_path, targets_json=two_targets())
    calls = []

    def fake_post(url, data=None, headers=None, timeout=None):
        calls.append((url, headers.get("X-GLR-Agent-Token")))
        return FakeResponse(200)

    monkeypatch.setattr(agent.requests, "post", fake_post)
    assert agent.deliver_payload(cfg, sample_payload()) is True
    assert ("https://prod.example/api/attendance/punch", "ptok") in calls
    assert ("https://uat.example/api/attendance/punch", "utok") in calls
    # both watermarks advanced
    for t in cfg.targets:
        assert agent.last_delivered_time(t) is not None


def test_failed_target_is_isolated_and_queued(monkeypatch, tmp_path):
    cfg = make_config(monkeypatch, tmp_path, targets_json=two_targets())
    prod, uat = cfg.targets

    def fake_post(url, data=None, headers=None, timeout=None):
        if "uat" in url:
            raise agent.requests.RequestException("network down")
        return FakeResponse(200)

    monkeypatch.setattr(agent.requests, "post", fake_post)
    # a punch that reaches prod but not uat is NOT "fully delivered"
    assert agent.deliver_payload(cfg, sample_payload()) is False

    # prod delivered + watermark advanced, nothing queued
    assert agent.last_delivered_time(prod) is not None
    assert not prod.queue_file.exists()

    # uat queued exactly one punch, watermark NOT advanced
    assert uat.queue_file.exists()
    assert len(uat.queue_file.read_text(encoding="utf-8").strip().splitlines()) == 1
    assert agent.last_delivered_time(uat) is None


def test_flush_redelivers_only_failed_target(monkeypatch, tmp_path):
    cfg = make_config(monkeypatch, tmp_path, targets_json=two_targets())
    prod, uat = cfg.targets

    def fail_uat(url, data=None, headers=None, timeout=None):
        if "uat" in url:
            raise agent.requests.RequestException("network down")
        return FakeResponse(200)

    monkeypatch.setattr(agent.requests, "post", fail_uat)
    agent.deliver_payload(cfg, sample_payload())
    assert uat.queue_file.exists()

    # uat recovers -> flushing its queue clears it and advances its watermark
    monkeypatch.setattr(agent.requests, "post",
                        lambda url, data=None, headers=None, timeout=None: FakeResponse(200))
    agent.flush_queue(cfg, uat)
    assert not uat.queue_file.exists()
    assert agent.last_delivered_time(uat) is not None


def test_dry_run_posts_nothing_and_writes_no_state(monkeypatch, tmp_path):
    cfg = make_config(monkeypatch, tmp_path, targets_json=two_targets(), dry_run=True)

    def boom(*args, **kwargs):
        raise AssertionError("dry run must not POST")

    monkeypatch.setattr(agent.requests, "post", boom)
    assert agent.deliver_payload(cfg, sample_payload()) is True
    for t in cfg.targets:
        assert agent.last_delivered_time(t) is None
        assert not t.state_file.exists()
