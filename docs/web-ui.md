# React Web — Phase 8

`jdbc-test-web/` is an independent npm project in the monorepo. It is **not a
Maven module**. Phase 8 builds a standalone development UI; serving its static
assets from a production deployment is reserved for Phase 9. Phase 8 is formally
accepted. Phase 9 now implements same-origin static hosting and Docker packaging;
see `deployment.md` and its explicitly bounded `phase9-verification.md` evidence.
Development remains Vite :5173 → Server :8080.

## Stack and structure

- React 19, TypeScript (`strict`, `noUnusedLocals`, `noUnusedParameters`), Vite 7.
- Tailwind CSS 4 and local shadcn/ui components. Button/Input originate from
  the official new-york-v4 registry, customized with the product's tokens;
  Dialog, AlertDialog and Tabs compose Radix primitives. Sonner handles toasts.
- React Router, TanStack Query, TanStack Table, modular ECharts (pie outcomes).
- Standard `fetch`, native `EventSource`, lucide-react, self-hosted fonts.
- Vitest/React Testing Library and Playwright. No Redux, external store, Axios,
  additional chart library or form framework.

```text
jdbc-test-web/
├── src/app/                   Router and QueryClient
├── src/api/                   Fetch client, feature APIs and query keys
├── src/components/            Shell, tables, chart and accessible primitives
├── src/features/dashboard/
├── src/features/drivers/
├── src/features/connections/
├── src/features/runs/
├── src/hooks/                 EventSource lifecycle
├── src/lib/                   Formatters, status presentation, file checks
├── src/types/                 Centralized resource DTOs and status unions
├── src/test/                  Test-only fixtures, fetch/transport harness
└── e2e/                       Separate mocked and real browser projects
```

The Node engine baseline is **>=22.12.0** (use a supported LTS release; tested
with Node 24.11.1 and npm 11.6.2). Only `package-lock.json` is used. Dependency
versions resolved by the lockfile are authoritative; npm must not be mixed with
other package managers.

## Development startup

The Server requires **Platform PostgreSQL**, a persistent 32-byte Base64
`JDBC_TEST_MASTER_KEY`, a writable data directory and a built Worker executable.
Target PostgreSQL is a different database used by the tests. See
`platform-database.md`, `driver-management.md`, `connection-management.md` and
`test-run-management.md` for the persistence, credential and execution boundaries.

From the repository root, build/install the Java modules once:

```bash
./mvnw -DskipTests install
```

Set the Server environment in its terminal (replace the example credentials;
do not commit them). Keep the master key stable when reusing the platform data:

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/jdbc_platform'
export SPRING_DATASOURCE_USERNAME='jdbc_platform'
export SPRING_DATASOURCE_PASSWORD='your-platform-password'
export JDBC_TEST_MASTER_KEY='your-32-byte-base64-master-key'
export JDBC_TEST_DATA_DIRECTORY="$PWD/data"
export JDBC_TEST_WORKER_EXECUTABLE="$PWD/jdbc-test-worker/target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar"
./mvnw -pl jdbc-test-server spring-boot:run
```

In a separate terminal:

```bash
cd jdbc-test-web
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` to `http://localhost:8080`.
`timeout: 0` and `proxyTimeout: 0` avoid truncating long-lived SSE streams;
the proxy streams `text/event-stream` rather than replacing it with JSON.
`VITE_API_BASE_URL` optionally changes the API base at build time, but the
same-origin proxy is the default and requires no Server CORS changes.

## Pages and workflows

| Route | Function |
| --- | --- |
| `/` | Genuine total runs/catalog metrics, bounded recent runs and outcomes |
| `/drivers` | Paginated/sorted packages, validate and confirmed deletion |
| `/drivers/new` | Metadata and native multi-JAR upload, size/count checks |
| `/drivers/:id` | Metadata editing, artifacts/fingerprints, validation result |
| `/connections` | Paginated/sorted targets, saved probe, Start Run/delete |
| `/connections/new` | Dynamic adapters/drivers, draft probe and independent save |
| `/connections/:id` | Edit, saved probe, clear credential and deletion |
| `/runs` | Server pagination, status filtering, permitted sorting |
| `/runs/new` | Connection → Driver/password → Profile/modules → Review/consent |
| `/runs/:id` | REST snapshot, SSE progress/cancel, cases and report tabs |

The sidebar is persistent on desktop and a drawer on tablet. Tables scroll
horizontally within their panels. Status text accompanies semantic color;
date/time uses the browser's local timezone. UUIDs and fingerprints can be copied.
No fake Settings, Logs, user account or notification controls are present.

Driver upload sends a JSON `metadata` multipart part and repeated `files` parts.
Native file/drop events require no upload library. Limits are 20 artifacts,
100 MiB each, 250 MiB total; these are early UX checks, **not** a security boundary.
The Server still validates every request. Existing artifacts are immutable.
Ambiguous classes are offered only when the Server returns `properties.candidates`.

Connection database type is an open text label, distinct from the API-loaded
adapter. Only validated packages are offered, with paginated browsing. Save and
Test are independent. Edit does not fill saved credentials into password inputs.
Saved Test uses persisted settings; save edited metadata before testing it.
After a request finishes the password input is cleared, so re-enter an unsaved
credential if another action needs it. To replace an edit credential, check
Save password; Clear saved password sends no replacement password.

Run profile/module names and counts come from `/api/test-catalog`, not constants.
The MVP exposes module selection, not a giant scenario checklist. Module-only
preview sums disjoint catalog module counts; final selection remains the Server's
responsibility. Core/Full use the catalog's profile counts. Destructive consent
starts unchecked and is mandatory. `201` navigates immediately to the new Run.

## API and state

Types were mapped from the actual Server Controllers/records/enums, including
nullable fields and the SSE publisher. Resource responses are consumed directly;
there is no universal success envelope or duplicated frontend domain model.

`src/api/client.ts` handles the API base, JSON/204/multipart, HTTP status,
AbortSignal, network failures and Problem responses. `ApiError` retains HTTP
status, stable `code` and optional properties. Known errors get user-facing
messages plus the code; unknown errors display sanitized Server detail. No
raw exception object is rendered in a toast. 404 resources have an error state;
409 delete conflicts explain historical references. 409 `RUN_ALREADY_FINISHED`
refreshes the snapshot instead of making the Run failed. 422 validation/selection
errors stay actionable on the current form.

TanStack Query owns all Server state. Feature query keys separate lists, details,
results and reports. Mutations invalidate the relevant resource family. Forms,
tabs, confirmations, wizard choices and transport connection state use local
React state. No data is copied to a global store.

## SSE

Run Detail first loads `GET /api/runs/{id}`. Only a nonterminal Run opens
`GET /api/runs/{id}/events` with `EventSource`. Named snapshot, status, module,
case, progress and terminal events update the **same Query cache**. Case finish
invalidates paginated results. Terminal events close the source and invalidate
Run queries to fetch final counters/timing. Unmount removes listeners and closes.
Stale events cannot reverse an already terminal cache entry; wrong Run IDs are
ignored. Snapshot fields are allowlisted against the resource shape.

An SSE error shows “Live updates reconnecting…” while allowing native reconnect;
it never changes Server Run status. A 10-second REST fallback refetches active
details in case a proxy/transport disconnect loses the final event. SSE remains
the primary live channel, not an independent state store.

## Charts and existing API limitations

No `/api/dashboard/summary` exists. Dashboard asks for **only eight recent runs**
and uses `totalElements` for the real global Run count. Outcome/chart and recent
case metrics explicitly say they apply to the loaded recent runs. No fake global
pass rate, duration average or trend is displayed. Catalog totals are reliable
inventory metrics, not cumulative executed test totals.

Run lists currently offer only a status filter, not connection/driver filters.
Results support status **or** module filtering: the existing Server gives status
precedence, so the UI disables module selection while status is active. Pagination
and supported sorting are server-side; the browser never loads all historical rows.

The report DTO is `{summary, failures}`. Report shows captured environment,
timing, selection and actual failed/aborted results with an ECharts outcome donut.
ABORTED is preserved; its count is `completedCases - passed - failed - skipped`,
the verified Phase 7 aggregate rule. No per-module aggregate endpoint exists,
so no synthetic module chart is made. `report.json` is fetched only on request,
shown as formatted plain text and downloadable via a browser Blob. Missing raw
reports on cancelled/interrupted runs have a normal error state. There is no
safe diagnostics API, so no Logs tab.

Dedicated Dashboard aggregate/trend and module-result APIs are **future product
work**, not secretly added backend endpoints in Phase 8.

## Security

- Passwords use `type=password`, local component memory only. No localStorage,
  sessionStorage, URL/query parameter, log or request-variable query cache stores
  them. Mutations use closure-local inputs, void variables and `gcTime: 0`;
  password state clears after request settlement and on relevant dialog close.
- GET resource types have no password/ciphertext, storage key or physical workspace
  path. Neither saved password nor artifacts' physical storage is requested.
- Messages, failures, stack traces and report JSON are React text nodes (`pre`
  for trace/JSON), never HTML injection. ECharts tooltip uses rich-text rendering.
- No `console.log`, `alert`, browser `confirm` or production fake API/data.
- Backend validation/sanitization/encryption, not client checks, remain authoritative.
- The development proxy is a convenience, not authentication or a production
  perimeter. This phase does not claim public-network production readiness.

## Testing

```bash
cd jdbc-test-web
npm install
npm run lint
npm run test
npm run build
npx playwright install chromium
npm run test:e2e
```

Vitest/RTL uses test-only contract fixtures and a real-fetch boundary mock;
there is no production fake API. Tests cover routing, lists/empty/errors,
multi-JAR limits/upload, validation/deletion conflicts, dynamic adapter/driver
forms, password handling/probes, profiles/modules/consent/create, all Run states,
SSE lifecycle, cancel races at the HTTP boundary, result paging/filtering and
report/raw JSON. ECharts DOM is mocked in form tests; browser tests exercise the
actual chart.

`test:e2e` runs only the **API-mocked** Playwright project: a complete UI flow and
empty Dashboard/responsive widths 1280, 1024 and 768. It is not a real system E2E.

For the **real** project, first start a clean Platform PostgreSQL, a separate
dedicated Target PostgreSQL, Server with the built Worker, and provide:

```bash
export JDBC_TEST_E2E_DRIVER_JAR='/absolute/path/to/postgresql-42.7.5.jar'
export JDBC_TEST_E2E_DRIVER_VERSION='42.7.5'
export JDBC_TEST_E2E_TARGET_URL='jdbc:postgresql://127.0.0.1:55433/jdbc_ui'
export JDBC_TEST_E2E_USERNAME='jdbc_ui'
export JDBC_TEST_E2E_PASSWORD='your-target-password'
npm run test:e2e:real
```

Real tests intentionally fail fast if credentials/artifact are absent: there is
no mocked fallback. They create persistent driver/connection/history data in the
dedicated test Platform database and require an initially empty artifact store
(a repeated identical artifact upload is rejected by design). Use a fresh test
Platform database/data directory on rerun. Do not point these tests at shared or
production data. Secrets are environment-only and trace collection is disabled
for the real project to avoid recording form/request credentials.

`PLAYWRIGHT_CHANNEL=chrome` optionally uses an already installed Google Chrome
instead of downloading Playwright Chromium (used in this environment after a CDN
download failure). The same assertions execute with no mocked real requests.
Both projects start Vite themselves or reuse an existing dev server. Screenshots
and reports live in ignored `test-results/`; `dist` and `node_modules` are ignored.

From the repository root, preserve the Java baseline:

```bash
./mvnw clean verify
./mvnw -Pserver-integration verify
```

See `phase8-verification.md` for actual command results and real acceptance evidence.
