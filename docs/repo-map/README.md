# GL-R-ERP Repo Map

A **domain-level** map of this repo for both humans and coding agents. Each note under
[`domains/`](domains/) is one **vertical slice** — the backend package(s), the frontend
feature(s), and the DB schema/migrations for one domain, in one place — so you can find
where a feature lives, and how features relate, without grepping the whole tree.

## Open it in Obsidian
1. Obsidian → **Open folder as vault** → pick this `docs/repo-map/` folder.
2. Open [`INDEX.md`](INDEX.md) (the entry point), then open the domain you care about.
3. Turn on **Graph view** — the `[[wikilinks]]` between notes (the **Related** section of
   each domain) *are* the map. Filter the graph with the `#domain/*` tags.

No plugins required; everything is plain Markdown. It also renders fine on GitHub.

## For coding agents
Read [`INDEX.md`](INDEX.md) first, then the single relevant domain note, instead of
scanning the source tree — that is the whole point of this map (orient fast, read less).
Each note's **Gotchas** are pointers; `CLAUDE.md` / `AGENTS.md` at the repo root remain
authoritative for business rules and history.

## Keeping it fresh
The curated model (grouping, relations, gotchas) and the live file-listing walker both
live in one script. After a big merge, re-sync the file listings with:

```
python3 docs/repo-map/_tools/generate.py
```

It rewrites `INDEX.md` + `domains/*.md` and stamps each with the `develop` sha it synced
to. Domains are intentionally coarse (packages rarely move), so the map ages slowly; the
`synced: develop@<sha>` front-matter tells you how stale a note is.

To add/rename a domain or edit a gotcha, edit the `DOMAINS` model in
[`_tools/generate.py`](_tools/generate.py) and re-run — don't hand-edit the generated
notes (a re-run overwrites them).
