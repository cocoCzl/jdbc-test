# Standard JDBC test examples

English | [简体中文](README.zh-CN.md)

Web users upload drivers, configure connections and choose built-in tests; no adapter or SQL packages.
Run CLI examples from the source root against dedicated test databases only. Keep generated config,
credentials and drivers outside Git.

```sh
python3 -B scripts/runner.py init --adapter postgresql \
  --url jdbc:postgresql://localhost:5432/test_db --username jdbc_tester \
  --namespace owned_schema --password-env DB_PASSWORD --consent \
  --driver-dir local-drivers/postgresql --connection-mode driver_manager
python3 -B scripts/runner.py run configs/config.local.json
```

Supply `DB_PASSWORD` through your secret mechanism before execution. `--adapter` accepts built-in
IDs from `adapters/catalog.json` only. MySQL uses `--adapter mysql` and a dedicated database; Oracle
uses `--adapter oracle` and its dedicated account schema. See [Usage](../docs/usage.md).

For a proprietary product explicitly reuse a built-in dialect:

```sh
python3 -B scripts/runner.py init --adapter postgresql --compatible-dialect \
  --driver-class com.example.jdbc.Driver --driver-dir local-drivers/vendor \
  --url jdbc:vendor://localhost:9000/test_db --username jdbc_tester \
  --namespace owned_schema --password-env DB_PASSWORD --consent \
  --connection-mode driver_manager --output configs/vendor.local.json
```

This does not assert another product's identity/version range; actual JDBC metadata remains independent.
Capability declarations, standard assertions, scope and preparation checks remain active. Provenance is
local, without formal eligibility. Dialect reuse guarantees neither preparation success nor compatibility.
Adaptation changes belong in `adapters/<id>/adapter.json`, `ddl`, `dml`, `pool` and source, then rebuild/deploy.

`--driver-dir` accepts one JAR or a directory containing the driver and all dependencies; all JARs are
fingerprinted. `--connection-mode` supports `driver_manager`/`hikari`. Repeat `--property KEY=VALUE`
for ordinary properties or `--property-env KEY=ENV_NAME` for secrets. Use dedicated user/password
arguments and never embed credentials in URLs.

[Internal driver A/B regression](../docs/internal-regression.md) keeps configuration and case selection
identical and changes only driver artifacts.
