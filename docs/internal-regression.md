# Internal driver upgrade regression

English | [简体中文](internal-regression.zh-CN.md)

An internal baseline is an explicitly selected snapshot of a CLI report. It does not change
`adapter.trust`, `formal_eligible`, known differences or the original report. Private drivers using bundled adapters or explicit compatible dialect selection can use this workflow.
Historical local/candidate provenance remains readable without enabling retired package imports. Internal comparison is not JDBC certification or
maintainer endorsement. The default `compare --scope formal` still requires formal eligibility;
both scopes now require the same versioned comparison evidence. Historical JSON is not upgraded.

## Run A, select, run B, compare

Use JDK 21, the Maven wrapper and an authorized dedicated test database/schema. Generate connection
configuration with `init --adapter postgresql` or another built-in ID. Private drivers use `--driver-dir`
and `--driver-class`; explicitly add `--compatible-dialect` for dialect reuse. No adapter package is
created or uploaded. See [examples](../examples/README.md).
Add these fields to the configuration (replace the example identity/build/mode):

```json
{
  "regression": {
    "database_identity": "isolated-vendor-test-instance",
    "server_build": "exact-server-build",
    "database_mode": "native",
    "environment_key_env": "JDBC_TEST_REGRESSION_KEY"
  },
  "db": {
    "driver": {"kind": "local", "path": "local-drivers/A"}
  }
}
```

Merge these fields with the existing configuration; keep its connection, password-env, adapter,
namespace consent and selection fields. `database_identity`, `server_build` and `database_mode`
are explicit operator attestations. Record the actual build and mode, including `native` where
applicable. The CLI cannot independently prove a physical database's identity or server settings.
The comparison also requires matching runtime JDBC product/version and connection fingerprints.

Generate a random comparison key once and keep the same key available for A and B, including in CI:

```sh
export JDBC_TEST_REGRESSION_KEY="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
# Supply the configured DB_PASSWORD through your existing secret mechanism.
python3 -B scripts/runner.py run configs/config.local.json
python3 -B scripts/runner.py baseline create \
  --report report/private-db_A/report.json --output report/internal-baseline.json
# Edit only db.driver to select B (or replace the contents of its local directory).
python3 -B scripts/runner.py run configs/config.local.json
python3 -B scripts/runner.py compare --scope internal \
  --baseline report/internal-baseline.json --current report/private-db_B/report.json \
  --output report/driver-upgrade.json
```

Replace report paths with the paths printed by `run`. `baseline create` refuses to overwrite an
existing file: select another name to retain multiple baselines. The snapshot includes the complete
report and its SHA-256; subsequent edits are detected. This is an integrity check, not a signature.
The key must contain at least 32 UTF-8 bytes; use a generated random value. Loss/rotation of the key
requires a new baseline. Reports contain the key ID and keyed configuration digest, never the key,
connection URL, username or property values in the comparison evidence. Password rotation alone
is allowed; permissions remain an operator responsibility and invocation errors remain visible.
Do not share local `diagnostics.log` without reviewing it.

A `run` with test failures returns 1 and still writes its report. An otherwise complete report with
existing contract failures can be explicitly selected as a baseline. A baseline with preparation,
cleanup, missing execution or unevaluated plan requirements is rejected. CI must preserve the report
and run comparison even when the assessment returned 1; use the comparison exit code for a regression
gate, and the assessment exit code if the policy additionally requires a clean run.

## Evidence and allowed differences

| Dimension | Driver upgrade policy |
|---|---|
| JDBC driver name/version and every selected driver/dependency JAR | May change; before/after names and SHA-256 retained |
| Suite/assertion/executor sources, catalog, POMs and CLI definitions | Must match content fingerprints, including uncommitted source changes |
| Adapter manifest/version/revision/capabilities/known differences and SQL/pool assets | Must match content fingerprints |
| Actual selected stable scenario IDs | Exact match; additions/removals require a separate baseline |
| Database instance label, exact server build, compatibility mode | Explicit, nonempty matching operator attestations; limited conclusion |
| Runtime JDBC database product/version | Must be present and equal; configuration fallback is insufficient |
| JDK runtime/vendor, OS/version/architecture, actual timezone/locale/charset and framework dependency JARs | Must be present and equal |
| Connection endpoint/user, properties/Providers, driver class, direct/pool mode, timeout, execution/plan settings and locale/JVM environment options | Must match a keyed configuration fingerprint |
| Auto-created namespace name/object prefix | May vary; lifecycle policy must match |
| Existing namespace | Name must also match; disposable ownership/consent still required |

A local directory is the explicit driver dependency bundle: all its JARs are fingerprinted and
allowed to change. A Maven driver coordinate selects its JAR; use a local directory for extra driver
dependencies. Framework JAR changes are blocked. Runtime classpath evidence must identify every
JAR. Missing/inconsistent target data, unknown statuses, unexplained Maven failures and missing
fingerprints produce concrete errors; equality is never inferred from two absent fields.
Source/driver changes during execution are rejected. Resource SQL digests cover the resolved asset
roots conservatively, so unrelated asset edits may require re-establishing the baseline.

## Output and CI

The JSON comparison contains `purpose`, `allowed_changes`, per-dimension `checks`, `errors`,
`new_failures`, `fixed`, `unresolved_failures`, `coverage_changes`, `support_losses`,
`execution_issues`, plan assessment and both reports' provenance/content fingerprints.
Reviewed `known_difference` results remain visible as unresolved failures. A transition from supported
to legally unsupported is a support regression, without relabelling it a JDBC contract violation.

| Exit | Meaning |
|---|---|
| 0 | Comparable, complete, no new regression; existing failures/unsupported results can remain |
| 1 | New failure, loss of previously supported behavior, or newly unmet plan requirement |
| 2 | Incomparable, invalid input, missing/version-incompatible evidence, coverage change or execution/preparation problem |

`no_new_regressions=true` is different from `all_selected_passed=true`. Inspect unresolved failures
and plan evidence even after exit 0. A selected case that is skipped/not run/preparation-blocked
prevents a successful comparison even if the same issue was present before. Unselected cases remain
`USER_EXCLUDED`/`NOT_APPLICABLE` and are not counted as executed or as assessment failures.
No comparison writes to the database or rewrites source reports.

## Scope and key management

This workflow is CLI-only; it does not provide Web baseline management, independent physical-server
attestation, remote baseline storage/signatures, or SQL/product/vendor certification.
See [JDBC decision reference](jdbc-capability-contracts.md) for method-level interpretation.

Keep the key in your existing CI secret store or another private secret manager, and inject it only
through the named environment variable. Do not commit it, put it in adapter/report JSON, echo it or
run these commands with shell tracing enabled. It is a comparison HMAC key, not a database password,
report-signing key or certification token. Its key ID is a SHA-256 identifier; the report does not
contain the original key. The baseline and current run must use the same retained key; rotation or
loss makes their configuration fingerprints incomparable and requires rerunning/selecting a new
baseline. Deleting a key does not invalidate old report contents, but prevents producing matching
new comparison evidence.

The CLI removes the configured comparison key and the default key variable from the Maven/driver
child environment. Retained report and diagnostic serialization also redacts unexpected echoes
of the active comparison key, including values that need JSON escaping.
