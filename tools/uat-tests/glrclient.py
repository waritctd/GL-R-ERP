"""Session + CSRF HTTP client for the GL-R-ERP backend.

Mirrors the exact login/CSRF pattern verified live against the UAT stack: POST /api/auth/login sets
the GLR_HR_SESSION + XSRF-TOKEN cookies; every mutating request must echo the XSRF-TOKEN cookie value
back in the X-XSRF-TOKEN header (CSRF is enforced by SecurityConfig).
"""
import requests


class GlrClient:
    def __init__(self, base_url):
        self.base = base_url.rstrip("/")
        self.s = requests.Session()
        self.user = None

    def login(self, email, password):
        r = self.s.post(
            f"{self.base}/api/auth/login",
            json={"email": email, "password": password},
            timeout=30,
        )
        r.raise_for_status()
        self.user = r.json()["user"]
        return self.user

    def _h(self, extra=None):
        h = {"X-XSRF-TOKEN": self.s.cookies.get("XSRF-TOKEN", "")}
        h.update(extra or {})
        return h

    def get(self, path, **kw):
        return self.s.get(f"{self.base}{path}", timeout=30, **kw)

    def post(self, path, json=None, data=None, files=None, headers=None, **kw):
        return self.s.post(
            f"{self.base}{path}",
            json=json,
            data=data,
            files=files,
            headers=self._h(headers),
            timeout=30,
            **kw,
        )

    def patch(self, path, json=None, headers=None, **kw):
        return self.s.patch(
            f"{self.base}{path}", json=json, headers=self._h(headers), timeout=30, **kw
        )

    def logout(self):
        return self.s.post(f"{self.base}/api/auth/logout", headers=self._h(), timeout=30)
