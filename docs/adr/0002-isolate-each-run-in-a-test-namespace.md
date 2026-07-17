# Isolate each run in a Test Namespace

Each run operates only in a dedicated, disposable Test Namespace supplied by the user or created with a unique name during preflight. If the account cannot use or create such a namespace, the run stops before testing rather than falling back to a business schema, protecting user-managed and company database data.
