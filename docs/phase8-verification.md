# Phase 8 Verification

Date: **October 1, 2026**. Verdict: **PASS**. Phase 9 is **not started**;
progression requires the user's separate confirmation.

## Scope

Independent `jdbc-test-web/` npm project with strict TypeScript, React, Vite,
Tailwind/shadcn UI, Router, TanStack Query/Table and ECharts. No Maven module,
Server API/schema/Java changes, static resource copying, Docker build, Nginx or
production packaging are introduced. Existing Phase 7 changes remain untouched.

Functional routes are Dashboard, Driver list/upload/detail/metadata/validation,
Connection list/create/edit/draft/saved probe, Run history/wizard/detail with
SSE/cancel/paged results/report/raw JSON. All use the audited resource contracts.
Architecture, startup, security and API limitations are detailed in `web-ui.md`.

## Executed commands

Frontend commands execute inside `jdbc-test-web/`; Java/Python commands execute
at repository root. Node 24.11.1 / npm 11.6.2 / Java 21 / Docker Desktop available.

| Exact command | Tests run | Passed | Failed/errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| `npm install` | — | — | — | — | Success; audit 0 vulnerabilities |
| `npm run lint` | — | — | — | — | Success; 0 errors, 0 warnings |
| `npm run test` | 75 | 75 | 0 | 0 | 7 Vitest/RTL test files |
| `npm run build` | — | — | — | — | Strict tsc + Vite build success |
| `PLAYWRIGHT_CHANNEL=chrome npm run test:e2e` | 2 | 2 | 0 | 0 | **API-mocked** UI smoke/responsive |
| `PLAYWRIGHT_CHANNEL=chrome npm run test:e2e:real` | 1 | 1 | 0 | 0 | **Real** full-stack browser E2E |
| `./mvnw clean verify` | 96 | 96 | 0 | 0 | BUILD SUCCESS |
| `./mvnw -Pserver-integration verify` | 105 | 105 | 0 | 0 | 96 unit + 9 actual PostgreSQL IT |
| `python3 -m unittest discover -s scripts -p 'test_*.py'` | 30 | 30 | 0 | 0 | OK |
| `python3 scripts/runner.py adapters` | — | — | — | — | 5 actual adapters |
| `python3 scripts/runner.py coverage --profile core --format json` | — | — | — | — | 136 scenarios |
| `python3 scripts/runner.py coverage --profile full --format json` | — | — | — | — | 202 scenarios |

The real command also supplies `JDBC_TEST_E2E_DRIVER_JAR` and
`JDBC_TEST_E2E_PASSWORD` through environment variables, not command-line secret
values. The CDN initially reset/stalled downloading the Playwright browser;
installed Google Chrome ran the **same** browser assertions successfully. This is
not a Docker limitation or a skipped real test. Real-project traces are disabled
to avoid recording password forms/requests. Test artifacts are ignored.

## Unit/component coverage

- Routing, actual pagination totals, bounded Dashboard data, empty/error states.
- Driver upload/removal/multiple parts and count/single/total size checks;
  validate metadata, ambiguous class selection, metadata-only edit, 409 deletion.
- Dynamic adapters/validated driver selector, open database type, create/save
  without mandatory probe, savePassword true/false, draft/saved probes, clear
  credential and deletion/test errors. No password echo into edit inputs/cache.
- Core/Full counts taken from the catalog (also tested with a changed count),
  Custom Statement 23/Connection 17, select/clear all, required consent, immediate
  create navigation and temporary credential isolation.
- All nine Run presentation states, nullable detail fields, Query-cache SSE
  snapshot/status/module/case/progress/terminal updates, reconnect/unmount,
  wrong ID/unknown field rejection and no terminal reversal.
- Cancel success and already-finished 409 refresh; history status filtering;
  result server paging/status/module filters, all four result statuses, literal
  failure/stack text, report outcomes/environment and on-demand raw JSON.
- ECharts resize, observer disconnect and dispose; rich-text tooltip and
  accessible outcome description.

## Real PostgreSQL browser evidence

No `page.route` API mock is used by `e2e/real.spec.ts`. It launches Vite on 5173
against the real Spring Server on 8080, with separate Platform PostgreSQL and
Target PostgreSQL containers. Only dedicated temporary test databases are used.

The browser actually uploads `postgresql-42.7.5.jar` from the local Maven cache,
validates it, creates a connection, probes Target PostgreSQL, saves the credential,
selects Custom Statement/Connection with unchecked consent, creates both Runs,
observes completion, opens results/report/raw JSON and finds the Runs in history.

| Run | Module | Selected/persisted | Passed | Failed | Skipped |
| --- | --- | ---: | ---: | ---: | ---: |
| `6e9ff3ff-e5c9-4e2b-9f4f-33171306d74a` | statement | 23 | 23 | 0 | 0 |
| `cc0ddaef-2123-4715-bdd4-518b2c11954c` | connection | 17 | 17 | 0 | 0 |

Both are `SUCCEEDED`; Run detail/results/report/report.json return successfully.
Each result is PASSED and has the selected module only. Direct PostgreSQL
`test_run_selected_scenario` counts are statement=23 and connection=17;
`test_run_credential` has **0 remaining rows** after the runs.

For **each** Run the browser's actual EventSource through the Vite proxy receives:
`run.snapshot`, `run.status`, `case.started`, `case.finished`, `run.progress`,
`run.finished`. The terminal state/progress is observed in the real UI.

External Driver identity is established by the real probe's JDBC metadata
version **42.7.5**, distinct from the actual Server runtime Platform driver
**42.7.11** (confirmed on its JVM classpath), and both
Run snapshots referencing the uploaded package ID/fingerprint. The fingerprint
is `a8b149b560d0b74eacbaebc6c2429f1f149fff6ba77c1abc78d43d92a9add320`.
No uploaded JAR is added to the Server runtime classpath. The unchanged Server
integration suite additionally passes its external artifact/code-source/isolated
Worker assertions.

Real populated Dashboard and mocked empty Dashboard verify widths **1280, 1024
and 768** with no document-wide horizontal overflow. Real screenshots include
Dashboard/outcome tables and both reports; desktop/tablet screenshots were
visually inspected. Tables retain their own horizontal scrolling on tablet.
The browser reports no uncaught page errors. Raw report does not contain the
test password; localStorage/sessionStorage remain empty.

## Compatibility and limitations

The real catalog API confirms Total=202, Core=136, Full=202, Modules=17,
Connection=17, Statement=23. Python coverage independently confirms 136/202.
The default Java count remains 96; real Server integration remains 105.

No dedicated Dashboard aggregate API, Run connection/driver filters, per-module
report aggregate or safe diagnostics endpoint exists. UI explicitly bounds recent
statistics, offers only supported filters, omits invented charts/logs and retains
module-only Custom selection. These are documented scope limitations, not
failed Phase 8 requirements. No fake production data or business API is introduced.

No unresolved Phase 8 blocker remains. Production serving/routing, container
integration and deployment belong to Phase 9 and await confirmation.

After acceptance, the temporary Server/Vite processes and the two PostgreSQL
test containers were stopped. Their test data was not deleted. The unused browser
download was cancelled; this does not alter the successful Chrome test results.

## File inventory

Added: `jdbc-test-web/` (configuration/lockfile, centralized API/types, shell/UI,
feature pages, hooks/formatters, seven test files, test-only harness/fixtures,
mocked and real Playwright specs), `docs/web-ui.md`, this verification record.

Changed in this phase: `.gitignore`, `README.md`, `REFACTOR_PLAN_OPTIMIZED.md`,
`docs/current-architecture.md`, `docs/migration-notes.md`. No Java, Maven module,
SQL migration or Python source is changed by Phase 8.

Frontend implementation corrections found during regression: capture the native
FileList before clearing its input (avoids losing multi-JAR selection during a
deferred state update); preserve DOMException AbortError rather than turning
request cancellation into a network failure. Regressions fix both behaviors.
