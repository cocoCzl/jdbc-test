# Quick start

1. Install JDK 21, Maven 3.8+, and Python 3.9+.
2. Run `python3 scripts/runner.py adapters` to list bundled adapters.
3. Run `runner.py init` to generate a local JSON config. Never store a password in it.
4. Point the config at a dedicated database environment. Oracle requires a dedicated test account/schema.
5. Set the password environment variable and run `runner.py run`.
6. Read `report.json`, `report.md`, and `report.html`. Keep `diagnostics.log` local.
7. Save an accepted formal report as your baseline and use `runner.py compare` in CI.

A local adapter directory can be used instead of a bundled adapter ID. Unreviewed adapters produce local/experimental reports and cannot become formal baselines.
