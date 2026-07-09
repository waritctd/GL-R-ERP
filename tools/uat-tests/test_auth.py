import requests
import pytest

from helpers import assert_status, create_throwaway_login, login_client, unique


@pytest.mark.uat("AUTH-01", title="Login establishes session", priority="P0")
def test_auth01_login_session(base_url):
    client = login_client(base_url, "employee@uat.glr", "Uat@2026")
    r = client.get("/api/auth/me")
    assert_status(r, 200)
    user = r.json()["user"]
    assert user["email"] == "employee@uat.glr", user
    assert user["employeeId"] is not None, user


@pytest.mark.uat("AUTH-02", title="Temporary password flags mustChangePassword", priority="P0")
def test_auth02_temp_password_flag_and_change_endpoint(base_url, hr):
    employee, email, password = create_throwaway_login(hr, base_url, unique("AUTH02"))
    client = login_client(base_url, email, password)
    assert client.user["mustChangePassword"] is True, client.user
    new_password = "Changed@2026A"
    r = client.post(
        "/api/auth/change-password",
        json={"currentPassword": password, "newPassword": new_password},
    )
    assert_status(r, 200)
    assert r.json()["user"]["mustChangePassword"] is False
    relogin = login_client(base_url, email, new_password)
    assert relogin.user["id"] == employee["id"]


@pytest.mark.uat("AUTH-02", title="Forced change-password screen", priority="P0")
def test_auth02_forced_change_screen_manual():
    pytest.skip(reason="manual/UI")


@pytest.mark.uat("AUTH-03", title="Wrong password attempts trigger lockout", priority="P0")
def test_auth03_lockout_uses_throwaway_account(base_url, hr):
    _, email, password = create_throwaway_login(hr, base_url, unique("AUTH03"))
    session = requests.Session()
    headers = {"X-Forwarded-For": "203.0.113.103"}
    statuses = []
    for _ in range(6):
        r = session.post(
            f"{base_url}/api/auth/login",
            json={"email": email, "password": "wrong-password"},
            headers=headers,
            timeout=30,
        )
        statuses.append(r.status_code)
    assert statuses[:5] == [401, 401, 401, 401, 401], statuses
    assert statuses[5] == 429, statuses
    r = session.post(
        f"{base_url}/api/auth/login",
        json={"email": email, "password": password},
        headers=headers,
        timeout=30,
    )
    assert r.status_code == 429, r.text
    assert int(r.headers.get("Retry-After", "0")) > 0


@pytest.mark.uat("AUTH-04", title="Change password rejects old password", priority="P0")
def test_auth04_change_password_throwaway(base_url, hr):
    _, email, password = create_throwaway_login(hr, base_url, unique("AUTH04"))
    client = login_client(base_url, email, password)
    new_password = "NewPass@2026B"
    r = client.post(
        "/api/auth/change-password",
        json={"currentPassword": password, "newPassword": new_password},
    )
    assert_status(r, 200)
    old = requests.post(
        f"{base_url}/api/auth/login",
        json={"email": email, "password": password},
        headers={"X-Forwarded-For": "203.0.113.104"},
        timeout=30,
    )
    assert old.status_code == 401, old.text
    assert login_client(base_url, email, new_password).user["email"] == email


@pytest.mark.uat("AUTH-05", title="Session survives backend restart", priority="P1")
def test_auth05_restart_manual():
    pytest.skip(reason="manual/UI")


@pytest.mark.uat("AUTH-06", title="Logout invalidates session", priority="P0")
def test_auth06_logout_me_unauthorized(employee):
    assert_status(employee.get("/api/auth/me"), 200)
    r = employee.logout()
    assert r.status_code == 204, r.text
    r = employee.get("/api/auth/me")
    assert r.status_code == 401, r.text
