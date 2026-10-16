#!/usr/bin/env python3
"""Generate the GL-R-ERP Obsidian repo map.

ONE generator that is also the re-sync tool: the curated domain model (grouping,
relations, gotchas) lives here; the per-file listings are walked live from the tree, so a
re-run refreshes "what files are in each domain" without hand-editing every note.

Usage:
    python3 docs/repo-map/_tools/generate.py        # regenerate domains/*.md + INDEX.md

Open `docs/repo-map/` as an Obsidian vault; the [[wikilinks]] between domain notes drive
the Graph view. The notes are also written to be read by a coding agent that needs to
orient in the repo quickly — INDEX.md first, then the relevant domain note.
"""
from __future__ import annotations
import os
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve()
MAP_DIR = HERE.parent.parent              # docs/repo-map
REPO = MAP_DIR.parent.parent              # repo root
BACKEND_PKG = REPO / "backend/src/main/java/th/co/glr/hr"
FRONTEND_FEAT = REPO / "frontend/src/features"
MIGRATIONS = REPO / "backend/src/main/resources/db/migration"
GH = "https://github.com/waritctd/GL-R-ERP/blob/develop"


def sha() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "origin/develop"], cwd=REPO, text=True
        ).strip()
    except Exception:
        return "unknown"


# ── Curated domain model ────────────────────────────────────────────────────────────────
# backend:  package dirs under th/co/glr/hr
# frontend: feature dirs under frontend/src/features
# related:  other domain slugs (→ [[wikilinks]] → graph edges)
LAYERS = ["Sales / CRM", "HR", "Platform"]

DOMAINS = {
    # ── Sales / CRM ──────────────────────────────────────────────────────────────────────
    "sales-deal-ticket": {
        "title": "Sales Deal & Ticket",
        "layer": "Sales / CRM",
        "purpose": "The deal aggregate (1 ticket = 1 deal) and its sales-stage pipeline / actions.",
        "backend": ["ticket", "orderconfirmation", "approval"],
        "frontend": ["tickets", "sales"],
        "schemas": ["sales"],
        "migrations": ["V59 pricing_request_item", "V83 deal tracking + activity"],
        "related": ["pricing-flow", "quotation", "customer", "deposit-billing-finance", "import-fulfilment"],
        "gotchas": [
            "15-stage pipeline (DealStage); S12-S17 = per-factory procurement, S18/S19 = delivery.",
            "salesActions.js is the single source of which action shows at each stage.",
            "ticket.status vs paymentStatus vs fulfillmentStatus are THREE independent axes.",
        ],
    },
    "pricing-flow": {
        "title": "Pricing Flow",
        "layer": "Sales / CRM",
        "purpose": "Pricing request → factory quote → landed cost → CEO selling-price decision.",
        "backend": ["pricingrequest", "pricingcosting", "pricingdecision", "pricing", "factoryquote"],
        "frontend": ["pricingRequests", "ceoSettings"],
        "schemas": ["sales", "price_catalog"],
        "migrations": [
            "V61 factory_quote + costing foundation", "V109 formula engine (landed cost)",
            "V141 CEO owns costing", "V156 uncostable items", "V185 direct-deal item fields",
            "V187 CEO price mode",
        ],
        "related": ["catalog-price-import", "sales-deal-ticket", "quotation", "import-fulfilment"],
        "gotchas": [
            "LandedCostCalculator reads thickness/origin from the CATALOG LINK only (+ Sales-entered thickness fallback, 2026-10) — see CLAUDE.md.",
            "A line with no resolvable thickness/origin/sqm is set ASIDE as 'uncostable' (V156), not a hard 422.",
            "Mock reimplements SOME pricing math but NOT freight — do not trust mock for pricing. See CLAUDE.md 'Mock API contract'.",
            "ceoSettings = FX rates + pricing formula config (BOT fetch + manual entry).",
        ],
    },
    "catalog-price-import": {
        "title": "Catalog & Price Import",
        "layer": "Sales / CRM",
        "purpose": "The product price catalogue (import-managed, version-controlled) + brand reference.",
        "backend": ["catalog", "brand"],
        "frontend": ["catalog"],
        "schemas": ["price_catalog"],
        "migrations": ["V153 active-version filter", "V171 import provenance", "V172 size norm", "V194 priceable basis"],
        "related": ["pricing-flow", "quotation"],
        "gotchas": [
            "Catalogue reads filter price_list_versions.status = 'ACTIVE' (V153); non-active rows are invisible to pricing.",
            "price_catalog.product_prices is the price list; sales.catalog is a SEPARATE brand reference — different ids.",
            "product_prices.price_per_sqm is a GENERATED column; ~41% of real rows have NULL thickness_mm.",
        ],
    },
    "quotation": {
        "title": "Quotation",
        "layer": "Sales / CRM",
        "purpose": "Customer-facing quotation generation, revisions, and the rendered PDF/XLSX.",
        "backend": ["dealquotation", "customerquotation"],
        "frontend": ["quotations"],
        "schemas": ["sales", "customers"],
        "migrations": ["V165 deal_quotation_direct", "V176 quotation_item sqm_per_box", "V192.x per-sqm / mixed price modes"],
        "related": ["pricing-flow", "sales-deal-ticket", "customer", "deposit-billing-finance"],
        "gotchas": [
            "QuotationRenderer needs the licensed Thai fonts (backend/fonts/, gitignored) — a Git build embeds substitute fonts.",
            "Quotation carries its OWN frozen price snapshot, independent of the live pricing decision.",
        ],
    },
    "deposit-billing-finance": {
        "title": "Deposit · Billing · Finance",
        "layer": "Sales / CRM",
        "purpose": "Deposit notices, billing notes, tax invoices, payments, and sales commission.",
        "backend": ["deposit", "billing", "finance", "specialmoney", "commission"],
        "frontend": ["deposits", "finance", "commissions", "specialmoney"],
        "schemas": ["sales", "hr"],
        "migrations": ["V72 pricing_decision", "V188 remaining invoice document", "V189 billing note document"],
        "related": ["sales-deal-ticket", "quotation"],
        "gotchas": [
            "DepositNoticeService.issue gates on ticket.status='quotation_issued' AND paymentStatus='CUSTOMER_CONFIRMED' — else 409.",
            "Commission math is UNTOUCHABLE without explicit request (CLAUDE.md scope rule).",
            "PDF/XLSX render is a 'not supported in mock mode' stub — documents only truly render on the real backend.",
        ],
    },
    "import-fulfilment": {
        "title": "Import / Fulfilment",
        "layer": "Sales / CRM",
        "purpose": "Per-factory import requests, procurement steps, and delivery fulfilment.",
        "backend": ["importdeal", "importrequest", "procurement", "factory"],
        "frontend": ["fulfilment", "importDeal", "importProgress"],
        "schemas": ["sales"],
        "migrations": ["V77 factory_purchase_order", "V184 per-factory import_request (sales.import_request)"],
        "related": ["sales-deal-ticket", "pricing-flow"],
        "gotchas": [
            "TWO parallel import systems: legacy deal-level (fulfillmentStatus IR_ISSUED) vs per-factory V184 (sales.import_request).",
            "A deal is in the import scope only when salesStage >= PROCUREMENT AND fulfillmentStatus != null.",
            "An item must be linked to a factory before it enters the import lane.",
        ],
    },
    "customer": {
        "title": "Customer",
        "layer": "Sales / CRM",
        "purpose": "Customers, projects, contacts, designers, and Thai-address handling.",
        "backend": ["customer", "designer"],
        "frontend": [],
        "schemas": ["customers"],
        "migrations": ["V32 demo ticket customers (demo)"],
        "related": ["sales-deal-ticket", "quotation"],
        "gotchas": [
            "Customer UI lives inside tickets/quotations features rather than a standalone feature dir.",
        ],
    },
    # ── HR ────────────────────────────────────────────────────────────────────────────────
    "employee": {
        "title": "Employee & Self-service",
        "layer": "HR",
        "purpose": "Employee master, profile, and self-service edit/requests (incl. tax allowance).",
        "backend": ["employee", "profile"],
        "frontend": ["employees", "profile", "profileRequests", "requests", "taxAllowance"],
        "schemas": ["hr", "hr_restricted"],
        "migrations": ["V139 missing-role personas + hire dates (demo)"],
        "related": ["attendance", "leave", "overtime", "payroll", "auth-session"],
        "gotchas": [
            "DivisionAccessPolicy.roleFor derives a user's role from their division — role is NOT stored directly.",
        ],
    },
    "attendance": {
        "title": "Attendance",
        "layer": "HR",
        "purpose": "Punch clock, attendance records, corrections, and the work calendar.",
        "backend": ["attendance"],
        "frontend": ["attendance", "attendanceCalendar", "attendanceCorrection"],
        "schemas": ["hr"],
        "migrations": [],
        "related": ["overtime", "payroll", "employee"],
        "gotchas": [
            "AttendanceScopeIntegrationTest is the reference pattern for real-DB authz scope tests.",
            "POST /api/attendance/punch is one of the few anonymous-allowed endpoints (SecurityConfig).",
        ],
    },
    "leave": {
        "title": "Leave",
        "layer": "HR",
        "purpose": "Leave requests, balances, policy rules, and approval.",
        "backend": ["leave"],
        "frontend": ["leave"],
        "schemas": ["hr"],
        "migrations": [],
        "related": ["employee", "payroll", "attendance"],
        "gotchas": [
            "Leave/quota math is business-logic-sensitive — re-check against the spec before PR (CLAUDE.md).",
        ],
    },
    "overtime": {
        "title": "Overtime",
        "layer": "HR",
        "purpose": "Overtime filing and the two-step (manager → CEO) approval chain.",
        "backend": ["overtime"],
        "frontend": ["overtime"],
        "schemas": ["hr"],
        "migrations": [],
        "related": ["attendance", "payroll", "employee"],
        "gotchas": [
            "Issue #199: mock let HR approve OT but real OvertimeService returns 403 — verify authz on the real service.",
            "day_type / pay multiplier derive from hr.holiday, never from the client's claim.",
        ],
    },
    "payroll": {
        "title": "Payroll",
        "layer": "HR",
        "purpose": "Payroll runs, deductions, consents, tax, and the payroll draft etag.",
        "backend": ["payroll"],
        "frontend": ["payroll"],
        "schemas": ["hr", "hr_restricted"],
        "migrations": [],
        "related": ["employee", "attendance", "overtime", "leave"],
        "gotchas": [
            "Payroll/tax math is UNTOUCHABLE without explicit request (CLAUDE.md).",
            "computeDraftEtag is mirrored in the mock — a mock-driven test can't validate the algorithm, only plumbing.",
        ],
    },
    # ── Platform ────────────────────────────────────────────────────────────────────────
    "auth-session": {
        "title": "Auth & Session",
        "layer": "Platform",
        "purpose": "Login, session (JDBC-backed), CSRF, password reset, and the security filter chain.",
        "backend": ["auth", "config"],
        "frontend": ["auth"],
        "schemas": ["hr"],
        "migrations": ["V19 spring_session"],
        "related": ["employee"],
        "gotchas": [
            "SecurityConfig is default-deny; role checks live in CONTROLLERS, not the filter chain.",
            "CSRF is app-rolled (CsrfCookieFilter), NOT Spring Security — do not 're-enable' Spring CSRF.",
            "Real AuthService refuses {role}-only login with 403; demo accounts are email+password (Demo@2026).",
        ],
    },
    "notification-mail": {
        "title": "Notification & Mail",
        "layer": "Platform",
        "purpose": "In-app notifications and outbound email (quotations, resets, alerts).",
        "backend": ["notification", "mail"],
        "frontend": [],
        "schemas": ["hr"],
        "migrations": [],
        "related": ["auth-session", "deposit-billing-finance", "pricing-flow"],
        "gotchas": [
            "SalesNotificationMailer.NO_OP is used in tests / when SMTP is unconfigured — no mail actually sends.",
        ],
    },
    "activity-audit": {
        "title": "Activity & Audit",
        "layer": "Platform",
        "purpose": "The activity log (request filter + recorder) and the audit trail.",
        "backend": ["activity", "audit"],
        "frontend": ["activityLog"],
        "schemas": ["hr"],
        "migrations": [],
        "related": ["auth-session"],
        "gotchas": [
            "ActivityLogFilter wraps every request; it needs the JDBC template, so a DB/Flyway failure aborts the whole app at boot.",
        ],
    },
    "dashboard": {
        "title": "Dashboard",
        "layer": "Platform",
        "purpose": "Cross-domain rollups and the landing dashboards.",
        "backend": ["dashboard"],
        "frontend": ["dashboard"],
        "schemas": ["hr", "sales"],
        "migrations": [],
        "related": ["deposit-billing-finance", "sales-deal-ticket"],
        "gotchas": [],
    },
    "platform-common": {
        "title": "Platform / Common",
        "layer": "Platform",
        "purpose": "Shared infra: common utils, config, file attachments, and locations.",
        "backend": ["common", "config", "attachment", "location"],
        "frontend": ["locations"],
        "schemas": ["hr"],
        "migrations": [],
        "related": ["auth-session"],
        "gotchas": [
            "common/ holds ApiException, PdfDocumentWriter, ThaiText, ArGlrSequence — reused everywhere.",
        ],
    },
}


def list_backend_files(pkg: str) -> list[str]:
    base = BACKEND_PKG / pkg
    if not base.is_dir():
        return []
    out = []
    for p in sorted(base.rglob("*.java")):
        rel = p.relative_to(REPO).as_posix()
        out.append(rel)
    return out


def list_frontend_files(feat: str) -> list[str]:
    base = FRONTEND_FEAT / feat
    if not base.is_dir():
        return []
    out = []
    for p in sorted(list(base.rglob("*.jsx")) + list(base.rglob("*.js"))):
        if ".test." in p.name:
            continue
        out.append(p.relative_to(REPO).as_posix())
    return out


def render_domain(slug: str, d: dict, stamp: str) -> str:
    L = []
    schemas = ", ".join(d["schemas"])
    L.append("---")
    L.append(f"title: {d['title']}")
    L.append(f"layer: {d['layer']}")
    L.append(f"schemas: [{schemas}]")
    L.append(f"tags: [repo-map, domain/{slug}]")
    L.append(f"synced: develop@{stamp}")
    L.append("---")
    L.append(f"# {d['title']}")
    L.append(f"> {d['purpose']}")
    L.append("")
    L.append(f"**Layer:** {d['layer']}  ·  **DB schemas:** `{schemas or '—'}`")
    L.append("")

    # Backend
    L.append("## Backend")
    if d["backend"]:
        for pkg in d["backend"]:
            files = list_backend_files(pkg)
            dir_link = f"[{pkg}/]({GH}/backend/src/main/java/th/co/glr/hr/{pkg})"
            L.append(f"### `th.co.glr.hr.{pkg}` — {dir_link}")
            if files:
                for f in files:
                    L.append(f"- `{f}`")
            else:
                L.append("- _(no files found — package may have moved; re-run generator)_")
    else:
        L.append("_(no dedicated backend package — see related domains)_")
    L.append("")

    # Frontend
    L.append("## Frontend")
    if d["frontend"]:
        for feat in d["frontend"]:
            files = list_frontend_files(feat)
            dir_link = f"[{feat}/]({GH}/frontend/src/features/{feat})"
            L.append(f"### `features/{feat}` — {dir_link}")
            for f in files:
                L.append(f"- `{f}`")
            if not files:
                L.append("- _(no files found — feature may have moved; re-run generator)_")
    else:
        L.append("_(no dedicated frontend feature — UI lives inside a related domain)_")
    L.append("")

    # DB
    L.append("## DB")
    L.append(f"- Schemas: `{schemas or '—'}`")
    if d["migrations"]:
        L.append("- Key migrations: " + "; ".join(f"`{m}`" for m in d["migrations"]))
    L.append("")

    # Related
    L.append("## Related")
    if d["related"]:
        L.append(" · ".join(f"[[{r}|{DOMAINS[r]['title']}]]" for r in d["related"] if r in DOMAINS))
    else:
        L.append("_(none)_")
    L.append("")

    # Gotchas
    L.append("## Gotchas")
    if d["gotchas"]:
        for g in d["gotchas"]:
            L.append(f"- {g}")
        L.append("")
        L.append("> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.")
    else:
        L.append("_(none recorded)_")
    L.append("")
    return "\n".join(L)


def render_index(stamp: str) -> str:
    L = []
    L.append("---")
    L.append("title: GL-R-ERP Repo Map")
    L.append("tags: [repo-map, index]")
    L.append(f"synced: develop@{stamp}")
    L.append("---")
    L.append("# GL-R-ERP Repo Map")
    L.append("> A domain-level map of the repo: each note is one **vertical slice** "
             "(backend ↔ frontend ↔ DB) so you can find where a feature lives — and how "
             "features relate — without grepping the whole tree.")
    L.append("")
    L.append("**How to use**")
    L.append("- Open `docs/repo-map/` as an **Obsidian vault**; the `[[links]]` between notes "
             "drive the **Graph view** — that graph *is* the map.")
    L.append("- Start here, open the domain you're touching, follow **Related** to its neighbours.")
    L.append("- **Agents:** read this INDEX first, then the one relevant domain note, instead of "
             "scanning the tree. Treat each note's **Gotchas** as pointers — `CLAUDE.md` is authoritative.")
    L.append("")
    L.append(f"_Synced to `develop@{stamp}`. Re-run `python3 docs/repo-map/_tools/generate.py` after "
             "big merges to refresh the file listings._")
    L.append("")
    for layer in LAYERS:
        L.append(f"## {layer}")
        for slug, d in DOMAINS.items():
            if d["layer"] != layer:
                continue
            L.append(f"- [[{slug}|{d['title']}]] — {d['purpose']}")
        L.append("")
    return "\n".join(L)


def main() -> None:
    stamp = sha()
    (MAP_DIR / "domains").mkdir(parents=True, exist_ok=True)
    for slug, d in DOMAINS.items():
        (MAP_DIR / "domains" / f"{slug}.md").write_text(render_domain(slug, d, stamp), encoding="utf-8")
    (MAP_DIR / "INDEX.md").write_text(render_index(stamp), encoding="utf-8")
    print(f"generated {len(DOMAINS)} domain notes + INDEX.md (synced develop@{stamp})")


if __name__ == "__main__":
    main()
