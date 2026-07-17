# JDBC Driver Compatibility Testing

This project provides a reusable test framework for assessing JDBC driver compatibility and preventing regressions across heterogeneous databases.

## Language

**JDBC Driver Compatibility and Regression Test Framework**:
A reusable framework that runs a common JDBC API test suite against a configured database and driver, producing pass, fail, and capability-based skip results. It excludes business-SQL validation, performance benchmarking, and database-migration verification.
_Avoid_: General database test tool, business SQL test suite, performance test framework, migration test tool

**Primary User**:
A database vendor or JDBC driver maintainer who uses the framework to validate a driver release against common JDBC API expectations and identify compatibility regressions. Application developers are secondary users.
_Avoid_: General application developer, business user

**Database Adapter Package**:
A declarative, database-specific contribution containing connection configuration templates, capability declarations, and SQL fixtures that lets a new database run the framework without modifying its Java core.
_Avoid_: Database plugin requiring core code changes, built-in database type

**Compatibility Result**:
The classified outcome of a JDBC test: a failure indicates a violated common JDBC expectation; a skip indicates an unsupported declared capability; and a known difference indicates a documented, intentional vendor deviation.
_Avoid_: Test failure for every non-passing case, unsupported-feature failure

**Compatibility Baseline**:
JDBC 4.3 executed on Java 21, the versioned standard against which the framework's initial common JDBC expectations are assessed.
_Avoid_: Version-agnostic JDBC compatibility

**Compatibility Assessment**:
An evaluation based on this project's versioned JDBC 4.3 test suite; it communicates observed coverage and outcomes but is not an official JDBC certification or a claim of complete specification coverage.
_Avoid_: Official JDBC certification, complete JDBC compliance guarantee

**Regression Comparison**:
A comparison between a current compatibility assessment and an approved baseline assessment that identifies newly introduced failures, skips, or known differences.
_Avoid_: Isolated test report, manual version comparison

**Approved Baseline Assessment**:
A compatibility assessment that a database vendor or JDBC driver maintenance team has designated as its own comparison reference; the framework does not endorse or certify the assessed driver.
_Avoid_: Framework-certified driver, project-owned driver baseline

**Target Database Instance**:
A database instance supplied and operated by the framework user or their organization, against which the framework runs JDBC tests. The framework neither provisions nor owns the target database.
_Avoid_: Framework-managed database, automatically provisioned target database

**Test Namespace**:
A dedicated, disposable schema or equivalent database namespace in a target database that contains only objects created by one framework run. When the user does not provide one, the framework creates a uniquely named namespace and removes only that namespace after the run.
_Avoid_: Business schema, shared application namespace

**Namespace Provisioning Preflight**:
A safety check that confirms the test account can use the configured Test Namespace or create a dedicated one. Failure to meet this requirement stops the run before any JDBC test executes.
_Avoid_: Fallback execution in a business schema, table-prefix fallback

**Cleanup Failure**:
A distinct run outcome in which the framework could not remove the Test Namespace it created. The namespace and actionable diagnostics are retained for manual remediation, and the framework never widens deletion scope or automatically removes historical leftovers.
_Avoid_: Silent cleanup warning, broad automatic cleanup

**Shareable Assessment Report**:
A compatibility assessment report safe to share outside the target-database organization: it excludes credentials, tokens, JDBC URL parameters, hostnames, ports, database names, and usernames by default while retaining product, driver, Java-version, and test-outcome information.
_Avoid_: Raw diagnostic report, unredacted connection report

**JDBC 4.3 Test Suite**:
The framework's versioned suite covering all JDBC 4.3 standard interface areas. Common behavior is exercised by core tests, while tests for optional JDBC capabilities are still present and run or skip according to a Database Adapter Package declaration.
_Avoid_: Partial JDBC smoke test, optional-test-free core suite

**Incomplete Adapter Package**:
A Database Adapter Package missing the required SQL assets or capability declaration for a JDBC 4.3 test area. It is a configuration error that blocks the run; it is not a skipped capability.
_Avoid_: Implicitly skipped test area, undeclared unsupported capability

**Known Difference Record**:
An auditable declaration of an intentional vendor deviation, bound to a specific test, applicable database and driver-version range, rationale, external issue link, and review deadline. It is never a general-purpose exemption from failures.
_Avoid_: Blanket exception, undocumented known issue

**Official Known Difference**:
A Known Difference Record in a Database Adapter Package that has been reviewed and merged by project maintainers. Only official records participate in formal, comparable reports; user-created records are labelled as local overrides.
_Avoid_: Local override presented as formal result, unreviewed official exception

**Adapter Support Range**:
The declared database-product and JDBC-driver version ranges for which a Database Adapter Package's fixtures, capability declarations, and Known Difference Records are valid.
_Avoid_: Unversioned database support, database-name-only compatibility

**Adapter Range Preflight**:
A preflight that compares database and driver metadata with the selected Adapter Support Range. A mismatch blocks formal execution; an explicit experimental override is permitted only for a report labelled out of range and ineligible for baselines or regression comparisons.
_Avoid_: Silent adapter mismatch, out-of-range formal assessment

**Formal Assessment Provenance**:
The non-sensitive identity information recorded with a formal report: JDBC 4.3 Test Suite version, Database Adapter Package version or revision, actual database and driver versions, and JDBC driver JAR checksum. It makes a comparison reproducible without exposing connection details.
_Avoid_: Untraceable formal report, secret-bearing provenance

**Pinned Driver Source**:
A JDBC driver supplied either by fixed Maven coordinates or by a local JAR path, with its SHA-256 checked and recorded. Floating latest versions and arbitrary URL downloads are not accepted.
_Avoid_: Latest driver dependency, arbitrary driver download

**Python Launcher**:
The supported Python command-line entry point that simplifies configuring, running, and reporting JDBC compatibility assessments while delegating test execution to the project runtime.
_Avoid_: Unsupported convenience script, Python-based JDBC implementation

**Primary Entry Point**:
The Python Launcher is the documented default way for a new user to configure, run, and receive a JDBC compatibility assessment. Maven remains a supported advanced and CI entry point.
_Avoid_: Maven-only onboarding, Python-only test runtime

**Secure Local Configuration**:
A user-local connection configuration generated by the Python Launcher that selects an adapter and stores no password; credentials are resolved from environment variables and the generated file is excluded from version control.
_Avoid_: Committed company credentials, plaintext password configuration

**Zero-Dependency Python Core**:
The initialization, execution orchestration, result parsing, and basic JSON/Markdown reporting provided by the Python Launcher use only the Python standard library. Optional presentation enhancements may have separate dependencies.
_Avoid_: Mandatory pip bootstrap, dependency-heavy basic launcher

**Release Package**:
A versioned GitHub Release download containing the runnable framework and a published SHA-256 checksum, allowing users to run the Python Launcher without cloning the source repository.
_Avoid_: Source-clone-only distribution, unverified release archive

**CI Gate Result**:
The process exit status used by automated pipelines: new compatibility failures, an Incomplete Adapter Package, any preflight failure, or a Cleanup Failure block the pipeline; declared capability skips and Official Known Differences do not.
_Avoid_: All non-passing outcomes block CI, report-only CI execution

**Official Adapter Evidence**:
A non-sensitive, successful compatibility assessment report supplied with a proposed official Database Adapter Package, demonstrating at least one validated combination inside its declared support range. Project CI validates package structure but does not require access to the contributor's database.
_Avoid_: Unevidenced official adapter, project-operated vendor database validation

**Validated Combination**:
An exact database-product version and JDBC-driver version pair for which an Adapter Support Range has a published, successful assessment evidence. It is distinct from the broader declared range and does not imply every version within that range was run.
_Avoid_: Assumed tested range, inferred validation coverage

**Test Suite Version Compatibility**:
The rule that formal regression comparisons require the same JDBC 4.3 Test Suite version. Assessments from changed suites require a newly approved baseline and may not be automatically classified as regressions against prior-suite results.
_Avoid_: Cross-suite regression verdict, implicit baseline migration

**Canonical Assessment Format**:
A versioned JSON document that is the authoritative formal assessment data for CI, baselines, and regression comparison. Markdown and HTML reports are human-readable views derived from this document.
_Avoid_: HTML-only report, unversioned machine-readable report

**Local Diagnostic Log**:
A non-shareable log retained on the user's machine that may include full executed SQL, exception messages, and stack traces for troubleshooting. Shareable reports expose only sanitized error categories, test identifiers, and short summaries.
_Avoid_: Raw diagnostics in a shareable report, permanently hidden troubleshooting detail

**Adapter Distribution Boundary**:
The initial distribution rule that a Database Adapter Package is either bundled with an official release or supplied from an explicit local path. The framework does not automatically download third-party adapter packages.
_Avoid_: Automatic adapter marketplace download, implicit remote adapter dependency

**Local Adapter Assessment**:
An assessment run with a user-supplied local Database Adapter Package. It may execute the full test suite and produce results, but remains labelled local or experimental and cannot serve as a formal baseline or formal regression comparison until the adapter is reviewed and merged.
_Avoid_: Unreviewed formal assessment, local adapter certification

**Stable Test Identifier**:
A permanent, unique identifier for a JDBC test case, retained across Java class or method renames. Formal assessments, Known Difference Records, and regression comparisons reference this identifier rather than implementation names.
_Avoid_: Class-name-based test identity, mutable test identifier

**Local-Only Assessment Storage**:
The default storage policy under which assessment reports, approved baselines, and Local Diagnostic Logs are written only to user-controlled local or CI workspace storage. The framework never uploads them to an external service automatically.
_Avoid_: Automatic cloud upload, framework-managed report storage

**Default Serial Execution**:
The initial execution policy that runs JDBC tests one at a time in a Test Namespace to prioritize repeatable results over throughput. Parallel execution is a future opt-in only for tests proven independent.
_Avoid_: Default parallel execution, uncontrolled concurrent test access

**No-Retry Test Result**:
The policy that each JDBC test executes once and records that first result. Only connection-oriented preflight checks may have a limited, configurable retry; test retries never hide intermittent driver behavior.
_Avoid_: Flaky-test retry masking, automatic test rerun

**Environment or Execution Error**:
A run-blocking result caused by an unusable assessment environment, such as unavailable connectivity, authentication failure, insufficient privileges, or timeout. It is distinct from a JDBC Compatibility Result but still fails CI because no valid assessment was completed.
_Avoid_: Compatibility failure for infrastructure problems, non-blocking invalid assessment

**Initial Official Adapter Set**:
The Database Adapter Packages for PostgreSQL, GaussDB, MySQL, Oracle, and SQL Server that the first formal release commits to bringing up to official-adapter standards. Other databases may initially run only through Local Adapter Assessments.
_Avoid_: Unbounded first-release database support, unofficial adapter presented as initial official support

**Initial Validated Matrix**:
A deliberately small list of exact database-product and JDBC-driver version pairs with published evidence for each Initial Official Adapter, expanded only as additional combinations are actually assessed.
_Avoid_: Broad unverified initial version range, presumed compatibility matrix

**Minimum Privilege Declaration**:
The explicit list of least privileges an official Database Adapter Package requires, verified during preflight before testing. It identifies missing permissions instead of asking users to supply unrestricted database accounts.
_Avoid_: Implicit privileged account requirement, unrestricted database account

**Driver-Free Release Package**:
A Release Package that contains no third-party JDBC driver JARs. Users obtain licensed drivers through a Pinned Driver Source, while the framework validates and records the selected artifact.
_Avoid_: Redistributed vendor driver bundle, release-contained JDBC driver

**Formal Java Runtime**:
JDK 21, the only Java runtime formally supported for the initial JDBC 4.3 Test Suite baseline. Newer Java runtimes may be used experimentally but do not produce formal baselines until separately validated.
_Avoid_: Unqualified Java-version support, newer-Java formal baseline without validation

**Standard JDBC Scope**:
The cross-database formal assessment scope consisting only of JDBC 4.3 standard interfaces and their defined behavior. Vendor-proprietary JDBC APIs may have separate extension tests but never affect formal compatibility results.
_Avoid_: Vendor API certification, proprietary extension in cross-database score

**JDBC Type Coverage Matrix**:
The per-standard-type assessment matrix in the JDBC 4.3 Test Suite. Common types are core tests, while optional types such as ARRAY, SQLXML, ROWID, REF, and STRUCT are explicitly run or skipped based on declared capabilities and receive their own outcomes.
_Avoid_: Interface-only coverage claim, implicit type support

**Outcome Matrix**:
The human-readable assessment view that groups stable test results by JDBC interface and type, showing outcome counts and individual changes rather than reducing compatibility to one percentage score.
_Avoid_: Compatibility percentage, single-score driver ranking

**Launcher Core Commands**:
The minimal Python Launcher interface: `init` creates Secure Local Configuration, `run` performs preflight, validation, testing, and report generation, and `compare` compares a current report with an Approved Baseline Assessment.
_Avoid_: Manual multi-step launcher workflow, command-heavy onboarding

**Bilingual Documentation**:
The official documentation and quick-start material published in both Chinese and English, with English enabling international driver-maintainer collaboration and Chinese serving domestic users.
_Avoid_: Single-language project documentation

**Cross-Platform Runtime Support**:
The initial formal support commitment for Linux, macOS, and Windows. The Python Launcher, configuration, and path handling must not rely on Unix-shell-specific behavior.
_Avoid_: Unix-only launcher, platform-specific undocumented workflow

**Runtime Dependency Preflight**:
The Python Launcher check for Python, JDK 21, and Maven that reports missing prerequisites with copyable Chinese and English installation guidance. It never installs system software automatically.
_Avoid_: Automatic system dependency installation, unexplained launch failure

**Destructive-Operation Consent**:
The explicit local configuration acknowledgement required before a run may create or delete a Test Namespace. Without consent, `run` performs only preflight; CI enables the same permission through controlled configuration.
_Avoid_: Implicit namespace creation consent, unguarded destructive test setup

**Optional Container Runtime**:
An optional Docker-based execution environment that supplies a consistent framework runtime while always connecting to a user-provided Target Database Instance. It is not the default onboarding path.
_Avoid_: Container-provisioned target database, Docker-only workflow

**Release-Coupled Official Adapter**:
An official Database Adapter Package versioned and published together with a framework Release Package. This fixes the framework, test rules, and adapter revision as one reproducible assessment unit.
_Avoid_: Independently rolling official adapter, unpinned official adapter revision

**Initial Release Acceptance Criteria**:
The first formal release is complete when a user on Linux, macOS, or Windows can use `init`, `run`, and `compare` to safely assess one Initial Official Adapter and gate CI, and when a new database can run the full suite through a Local Adapter Assessment without Java-core changes before being reviewed into an official adapter.
_Avoid_: Source-only framework milestone, hard-coded-database-only release
