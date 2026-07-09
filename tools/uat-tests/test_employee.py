import pytest

from helpers import assert_status, create_employee, employee_by_code, self_employee, unique


@pytest.mark.uat("EMP-01", title="HR creates employee and code is generated", priority="P0")
def test_emp01_create_employee_code_generated(hr):
    created = create_employee(hr, unique("EMP01"), code=None)
    assert created["id"] > 0, created
    assert created["code"].startswith("GLR-"), created
    assert created["active"] is True, created


@pytest.mark.uat("EMP-02", title="HR edits assignment and history is retained", priority="P0")
def test_emp02_assignment_history(hr):
    created = create_employee(hr, unique("EMP02"), divisionId="GA", divisionTh="GA-ธุรการทั่วไป")
    r = hr.patch(
        f"/api/employees/{created['id']}",
        json={
            "divisionId": "IT",
            "divisionTh": "IT-เทคโนโลยีสารสนเทศ",
            "departmentTh": "แผนกไอที",
            "positionTh": "เจ้าหน้าที่ไอที",
        },
    )
    assert_status(r, 200)
    updated = r.json()["employee"]
    assert updated["divisionId"] == "IT", updated
    assert len(updated["assignments"]) >= 2, updated["assignments"]
    assert updated["assignments"][0]["current"] is True, updated["assignments"]


@pytest.mark.uat("EMP-03", title="Employee list supports pagination and filters", priority="P0")
def test_emp03_list_page_filter(hr):
    r = hr.get("/api/employees?divisionId=SA&active=true&page=0&size=2")
    assert_status(r, 200)
    body = r.json()
    assert body["page"] == 0 and body["size"] == 2, body
    assert body["total"] >= len(body["employees"]) >= 1, body
    assert all(e["divisionId"] == "SA" and e["active"] for e in body["employees"]), body["employees"]


@pytest.mark.uat("EMP-04", title="Employee sees own profile and cannot edit", priority="P0")
def test_emp04_own_profile_read_only(employee):
    own = self_employee(employee)
    assert own["email"] == "employee@uat.glr", own
    assert own.get("salary") is None, own
    r = employee.patch(f"/api/employees/{own['id']}", json={"phone": "0811111111"})
    assert r.status_code == 403, r.text


@pytest.mark.uat("EMP-05", title="Employee submits profile-change request", priority="P0")
def test_emp05_submit_profile_request(employee):
    r = employee.post(
        "/api/profile-requests",
        json={
            "fieldKey": "phone",
            "fieldLabel": "Phone",
            "oldValue": "0800000000",
            "newValue": "0800000505",
        },
    )
    assert_status(r, 200)
    req = r.json()["profileRequest"]
    assert req["status"] == "pending", req
    listed = employee.get("/api/profile-requests").json()["profileRequests"]
    assert any(row["id"] == req["id"] and row["status"] == "pending" for row in listed), listed


@pytest.mark.uat("EMP-06", title="HR approves profile request and applies it", priority="P0")
def test_emp06_approve_profile_request(employee, hr):
    new_phone = "0800000606"
    r = employee.post(
        "/api/profile-requests",
        json={
            "fieldKey": "phone",
            "fieldLabel": "Phone",
            "oldValue": "",
            "newValue": new_phone,
        },
    )
    assert_status(r, 200)
    req_id = r.json()["profileRequest"]["id"]
    r = hr.patch(f"/api/profile-requests/{req_id}", json={"status": "approved", "reviewerNote": "OK"})
    assert_status(r, 200)
    reviewed = r.json()["profileRequest"]
    assert reviewed["status"] == "approved", reviewed
    employee_id = reviewed["employeeId"]
    r = hr.get(f"/api/employees/{employee_id}")
    assert_status(r, 200)
    assert r.json()["employee"]["phone"] == new_phone


@pytest.mark.uat("EMP-07", title="HR rejects profile request with note", priority="P1")
def test_emp07_reject_profile_request(employee, hr):
    r = employee.post(
        "/api/profile-requests",
        json={
            "fieldKey": "address",
            "fieldLabel": "Address",
            "oldValue": "",
            "newValue": "Rejected UAT address",
        },
    )
    assert_status(r, 200)
    req_id = r.json()["profileRequest"]["id"]
    r = hr.patch(
        f"/api/profile-requests/{req_id}",
        json={"status": "rejected", "reviewerNote": "Need clearer evidence"},
    )
    assert_status(r, 200)
    assert r.json()["profileRequest"]["status"] == "rejected"


@pytest.mark.uat("EMP-08", title="HR opens sensitive employee detail", priority="P0")
def test_emp08_hr_sensitive_detail_access(hr):
    employee = employee_by_code(hr, "GLR-0005")
    r = hr.get(f"/api/employees/{employee['id']}")
    assert_status(r, 200)
    detail = r.json()["employee"]
    assert detail["salary"] is not None, detail
    assert "sensitive" in detail, detail
