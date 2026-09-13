# Thai customer addresses

Source: https://github.com/open-admin-data/thailand-administrative-divisions
Revision: `eef739a534e26181c86073d69e9aeb527110cf1a` (2026-06-01), imported 2026-09-13.
License: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Attribution: Open Admin Data.
Changes: retain DOPA codes and bilingual names, remove Thai administrative prefixes, omit coordinates/villages, normalize postcode arrays.

Run `python3 scripts/thai-address/import.py` from any directory, or use `--source-dir` with the three upstream `all-provinces.json`, `all-districts.json`, `all-subdistricts.json` files for offline regeneration. The revision is pinned in the script. Review source changes before updating it.

Outputs: a Flyway repeatable SQL upsert seed and a JSON snapshot used only by the development mock API. Production reads PostgreSQL through cached, parent-scoped API queries; no external service is called while entering an address. Flyway seeds automatically after V176. Re-running SQL is idempotent and does not delete codes referenced by customer records. Future removals/postcode retirements require an explicit data migration; the importer is intentionally additive.

All 7,364 source subdistricts currently have one postcode. The database/API retain a one-to-many postcode relationship and UI offers a choice if multiple codes become available. Unknown postcodes are left blank, not invented; manual arbitrary overrides are not accepted.

`legacy_address` preserves pre-migration text. `address` remains the printable field consumed/snapshotted by documents. Structured saves validate the entire hierarchy and format that field server-side. Existing API callers may still send legacy `address`; such an explicit free-text replacement clears the structured fields to prevent stale codes. Updates that omit address fields leave the address untouched. Selecting structured locations never parses old text. Existing-customer address editing opens the same structured editor and keeps the original text visible for reference.
