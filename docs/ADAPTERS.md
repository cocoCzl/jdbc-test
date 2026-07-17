# Database Adapter Packages

An adapter is a directory containing `adapter.json` plus database-specific SQL assets. Pass its directory to `runner.py init --adapter /path/to/adapter` or set the generated config's `adapter` value to that path.

Required declarations include identity matching, supported database/driver ranges, capabilities, asset areas, namespace lifecycle, least privileges, validated combinations, and known differences.

Use [the local PostgreSQL example](../examples/adapters/postgresql-local/adapter.json) as a starting point. Local adapters always produce local/experimental assessments. To become official, an adapter must be reviewed, published with the framework release, include an exact validated database × driver combination, and reference sanitized evidence.

Known differences require a stable scenario ID, database and driver ranges, a reason, an external issue URL, a review deadline, and a trust marker. Local overrides never become official merely because a test passes.
