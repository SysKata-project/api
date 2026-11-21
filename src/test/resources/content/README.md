# Pilot bundle test inputs

Compiled without release mode from `SysKata-project/content` commit
`327a10f552549ae5b80ea4bf7dd78632aa951d75`, using that repository's
`scripts/compile_bundle.py` and the draft catalogs/assessment cases at
`drafts/0.2.0` and `drafts/0.3.0`. SHA-256 artifact hashes and source provenance
are recorded in each manifest. These are original SysKata synthetic curriculum
fixtures, with four and seven lessons respectively; they are **not releases**.

Both manifests retain `release_eligible: false`. Tests use a package-scoped reader
with explicit fixture capabilities; normal application loading rejects them.
Neither bundle contains a production lab evaluator. Trusted checkpoint A is a
required future activation gate before administration, and B before pilot
completion; the text-only draft must never substitute for those checkpoints.

Regenerate with the pinned source compiler rather than hand-editing the JSON:

```sh
.venv/bin/python scripts/compile_bundle.py build drafts/0.3.0/catalog.yaml \
  --cases drafts/0.3.0/assessment-cases.json --output /tmp/fresh-api-fixtures
```

Run from the content checkout at the recorded commit; repeat for 0.2.0.
Copy the three generated files into the matching test directory and review all
hash/source changes. These files are test resources and are absent from the API JAR.
