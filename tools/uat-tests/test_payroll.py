import pytest

from helpers import assert_status, money, payroll_line, payroll_preview


PROCESSED_PERIOD_ID = None


@pytest.mark.order(1)
@pytest.mark.uat("PAY-01", title="Payroll preview lists active employees", priority="P0")
def test_pay01_preview_lists_actives(hr):
    period = payroll_preview(hr, "2026-06-01")
    assert period["status"] == "PREVIEW", period
    assert period["id"] is None, period
    assert period["lineCount"] == len(period["lines"]), period
    assert period["lineCount"] >= 80, period["lineCount"]
    assert all(line["employeeCode"] not in {"GLR-0024", "GLR-0025"} for line in period["lines"])


@pytest.mark.order(2)
@pytest.mark.uat("PAY-02", title="June GLR-0005 includes seeded OT and commission", priority="P0")
def test_pay02_ot_commission_on_line(hr):
    period = payroll_preview(hr, "2026-06-01")
    line = payroll_line(period, "GLR-0005")
    assert money(line["overtimePay"]) == money("350.00"), line
    assert money(line["commissionPay"]) == money("600.00"), line


@pytest.mark.order(3)
@pytest.mark.uat("PAY-03", title="SSO cap and annualized tax calculated", priority="P0")
def test_pay03_sso_and_tax(hr):
    period = payroll_preview(hr, "2026-06-01")
    executive = payroll_line(period, "GLR-0001")
    assert money(executive["ssoWageBase"]) == money("17500.00"), executive
    assert money(executive["socialSecurity"]) == money("875.00"), executive
    assert money(executive["projectedAnnualIncome"]) > money("0"), executive
    assert money(executive["withholdingTax"]) > money("0"), executive


@pytest.mark.order(4)
@pytest.mark.uat("PAY-04", title="Special pay and unpaid leave recalculate net", priority="P1")
def test_pay04_inputs_recalculate(hr):
    baseline = payroll_preview(hr, "2026-09-01")
    base_line = payroll_line(baseline, "GLR-0005")
    adjusted = payroll_preview(
        hr,
        "2026-09-01",
        inputs=[
            {
                "employeeId": base_line["employeeId"],
                "specialPay1": "1000.00",
                "unpaidLeaveDays": "1.00",
                "spouseAllowance": "60000.00",
            }
        ],
    )
    adjusted_line = payroll_line(adjusted, "GLR-0005")
    assert money(adjusted_line["specialPayTotal"]) == money("1000.00"), adjusted_line
    assert money(adjusted_line["unpaidLeaveDeduction"]) > money("0"), adjusted_line
    assert money(adjusted_line["netPay"]) != money(base_line["netPay"]), (base_line, adjusted_line)


@pytest.mark.order(5)
@pytest.mark.uat("PAY-05", title="Process payroll period", priority="P0")
def test_pay05_process_period(hr):
    global PROCESSED_PERIOD_ID
    r = hr.post("/api/payroll/process", json={"payrollMonth": "2026-09-01", "inputs": []})
    assert_status(r, 200)
    period = r.json()["period"]
    assert period["status"] == "PROCESSED", period
    assert period["id"] is not None, period
    assert period["processedById"] == hr.user["employeeId"], period
    PROCESSED_PERIOD_ID = period["id"]


@pytest.mark.order(6)
@pytest.mark.uat("PAY-06", title="Bank export downloads text file", priority="P0")
def test_pay06_bank_export(hr):
    global PROCESSED_PERIOD_ID
    if PROCESSED_PERIOD_ID is None:
        r = hr.post("/api/payroll/process", json={"payrollMonth": "2026-09-01", "inputs": []})
        assert_status(r, 200)
        PROCESSED_PERIOD_ID = r.json()["period"]["id"]
    r = hr.get(f"/api/payroll/{PROCESSED_PERIOD_ID}/bank-export")
    assert_status(r, 200)
    assert r.headers["Content-Type"].startswith("text/plain"), r.headers
    assert f'glr-payroll-{PROCESSED_PERIOD_ID}.txt' in r.headers["Content-Disposition"], r.headers
    body = r.text
    assert body.startswith("GLR_PAYROLL|2026-09-01|"), body[:200]
    assert "GLR-0005" in body, body[:500]


@pytest.mark.order(7)
@pytest.mark.uat("PAY-07", title="Parallel-run payroll reconciliation", priority="P0")
def test_pay07_parallel_run_manual():
    pytest.skip(reason="manual/UI")
