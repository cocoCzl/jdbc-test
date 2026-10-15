package com.jdbctest.extension;

import com.jdbctest.config.Config;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * Explicit fixture placeholders and an ownership journal, not a SQL/driver sandbox or SQL
 * rewriter.
 */
public final class TestObjects {

  private static final Pattern TOKEN = Pattern.compile(
      "\\{\\{(?:(local|name):)?([a-z][a-z0-9_]*)}} ".trim());
  private static final Pattern CREATE = Pattern.compile(
      "(?is)^\\s*CREATE\\s+(TABLE|VIEW|INDEX|FUNCTION|PROCEDURE|SEQUENCE|TYPE)\\s+\\{\\{(?:local:)?([a-z][a-z0-9_]*)}}");
  private final String namespace;
  private final String objectPrefix;
  private final String dialect;
  private final String identifierQuote;
  private final String ledgerPath;
  private final LinkedHashMap<String, Map<String, Object>> journal = new LinkedHashMap<>();
  private final List<Map<String, Object>> history = new ArrayList<>();
  private int attempt;
  private String namespaceState = "EXISTING_AUTHORIZED";

  public TestObjects(Config config) {
    Config.NamespaceConfig scope = config.namespace;
    if (scope == null || scope.name == null || !scope.name.matches("[A-Za-z][A-Za-z0-9_]{0,62}")
        || !Set.of("schema", "catalog").contains(scope.selection) || !scope.destructiveConsent
        || scope.objectPrefix == null || !scope.objectPrefix.matches("j[0-9a-f]{20}")) {
      throw new IllegalArgumentException(
          "EXECUTION_SCOPE_REQUIRED: explicit namespace, consent and run object prefix required");
    }
    this.namespace = scope.name;
    this.objectPrefix = scope.objectPrefix;
    this.dialect = config.db.dialect;
    this.identifierQuote = config.db.getIdentifierQuote();
    this.ledgerPath = scope.ledgerPath;
    if (ledgerPath == null || ledgerPath.isBlank()) {
      throw new IllegalArgumentException("Object ledger path is required");
    }
  }

  public synchronized void recordNamespaceProvisioning(String state) {
    if (!Set.of("CREATING", "CREATED_RETAINED", "UNKNOWN").contains(state)) {
      throw new IllegalArgumentException("Invalid namespace state");
    }
    namespaceState = state;
    persist();
  }

  public String name(String logical) {
    if (!logical.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("Invalid logical object name");
    }
    try {
      String suffix = HexFormat.of().formatHex(
              MessageDigest.getInstance("SHA-256").digest(logical.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 6);
      String name = objectPrefix + "_" + suffix;
      return "oracle".equalsIgnoreCase(dialect) ? name.toUpperCase(Locale.ROOT) : name;
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public String qualified(String logical) {
    return quote(namespace) + "." + quote(name(logical));
  }

  private String quote(String name) {
    String quote = identifierQuote;
    return quote + name.replace(quote, quote + quote) + ("[".equals(quote) ? "]" : quote);
  }

  /**
   * Only authored placeholders are expanded; all other characters, comments and literals stay
   * intact.
   */
  public String sql(String template) {
    Matcher matcher = TOKEN.matcher(template);
    StringBuilder output = new StringBuilder();
    while (matcher.find()) {
      String replacement = "name".equals(matcher.group(1)) ? name(matcher.group(2))
          : "local".equals(matcher.group(1)) ? quote(name(matcher.group(2)))
              : qualified(matcher.group(2));
      matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(output);
    if (output.indexOf("{{") >= 0) {
      throw new IllegalArgumentException("Invalid object placeholder");
    }
    return output.toString();
  }

  public synchronized int create(Statement statement, String template) throws SQLException {
    Matcher header = CREATE.matcher(template);
    if (!header.find()) {
      throw new IllegalArgumentException(
          "Managed DDL requires an explicit CREATE object placeholder");
    }
    String kind = header.group(1).toUpperCase(Locale.ROOT);
    String logical = header.group(2);
    Map<String, Object> prior = journal.get(logical);
    if (prior != null && !"DROPPED".equals(prior.get("state"))) {
      throw new SQLException("Object already reserved by this run", "HY000");
    }
    if (journal.entrySet().stream().anyMatch(
        item -> !item.getKey().equals(logical) && name(logical).equals(
            item.getValue().get("name")))) {
      throw new SQLException("Physical object name collision", "HY000");
    }
    if (prior != null) {
      history.add(Map.copyOf(prior));
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("attempt", ++attempt);
    entry.put("logicalName", logical);
    entry.put("name", name(logical));
    entry.put("namespace", namespace);
    entry.put("kind", kind);
    entry.put("state", "CREATING");
    if (kind.equals("INDEX")) {
      Matcher parent = Pattern.compile("(?i)\\bON\\s+\\{\\{([a-z][a-z0-9_]*)}}").matcher(template);
      if (!parent.find()) {
        throw new IllegalArgumentException("Index owner table is required");
      }
      entry.put("parent", parent.group(1));
    }
    journal.put(logical, entry);
    persist(); // intent must reach disk before any DDL
    try {
      int count = statement.executeUpdate(sql(template));
      entry.put("state", "CREATED");
      persist();
      if (kind.equals("TABLE")) {
        recordDependents(statement.getConnection(), logical, entry);
      }
      return count;
    } catch (SQLException exception) {
      entry.put("state", "UNKNOWN");
      entry.put("sqlState", Objects.toString(exception.getSQLState(), ""));
      entry.put("vendorCode", exception.getErrorCode());
      persist();
      throw exception; // never DROP a collision or an uncertain CREATE
    }
  }

  /**
   * Dependent objects are evidence only; never independently DROP a driver-reported dependency.
   */
  private void recordDependents(Connection connection, String logical, Map<String, Object> entry) {
    List<Map<String, String>> dependents = new ArrayList<>();
    try {
      if (connection == null) {
        throw new SQLException("Metadata connection unavailable");
      }
      DatabaseMetaData metadata = connection.getMetaData();
      String catalog = "mysql".equalsIgnoreCase(dialect) ? namespace : connection.getCatalog();
      String schema = "mysql".equalsIgnoreCase(dialect) ? null : namespace;
      try (ResultSet rows = metadata.getPrimaryKeys(catalog, schema, name(logical))) {
        while (rows.next()) {
          addDependent(dependents, "CONSTRAINT", rows.getString("PK_NAME"));
        }
      }
      try (ResultSet rows = metadata.getIndexInfo(catalog, schema, name(logical), false, false)) {
        while (rows.next()) {
          addDependent(dependents, "INDEX", rows.getString("INDEX_NAME"));
        }
      }
      if ("oracle".equalsIgnoreCase(dialect)) {
        queryDependents(connection,
            "SELECT sequence_name FROM all_tab_identity_cols WHERE owner = ? AND table_name = ?",
            logical, "SEQUENCE", dependents);
        queryDependents(connection,
            "SELECT segment_name FROM all_lobs WHERE owner = ? AND table_name = ?", logical, "LOB",
            dependents);
        queryDependents(connection,
            "SELECT index_name FROM all_lobs WHERE owner = ? AND table_name = ?", logical,
            "LOB_INDEX", dependents);
        queryDependents(connection,
            "SELECT constraint_name FROM all_constraints WHERE owner = ? AND table_name = ?",
            logical, "CONSTRAINT", dependents);
      } else if ("postgresql".equalsIgnoreCase(dialect)) {
        queryDependents(connection,
            "SELECT seq.relname FROM pg_catalog.pg_class seq JOIN pg_catalog.pg_depend dep ON dep.objid = seq.oid JOIN pg_catalog.pg_class tab ON dep.refobjid = tab.oid JOIN pg_catalog.pg_namespace ns ON tab.relnamespace = ns.oid WHERE ns.nspname = ? AND tab.relname = ? AND seq.relkind = 'S' AND dep.deptype IN ('a', 'i')",
            logical, "SEQUENCE", dependents);
      }
      entry.put("dependenciesState", "RECORDED");
    } catch (SQLException exception) {
      entry.put("dependenciesState", "UNKNOWN");
      entry.put("dependenciesSqlState", Objects.toString(exception.getSQLState(), ""));
    }
    entry.put("dependentObjects", dependents);
    persist();
  }

  private void queryDependents(Connection connection, String sql, String logical, String kind,
      List<Map<String, String>> values) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, namespace);
      statement.setString(2, name(logical));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          addDependent(values, kind, rows.getString(1));
        }
      }
    }
  }

  private void addDependent(List<Map<String, String>> values, String kind, String name) {
    if (name != null) {
      var value = Map.of("kind", kind, "name", name, "namespace", namespace, "lifecycle",
          "PARENT_MANAGED");
      if (!values.contains(value)) {
        values.add(value);
      }
    }
  }

  public synchronized void drop(Connection connection, String logical) throws SQLException {
    Map<String, Object> entry = journal.get(logical);
    if (entry == null || !"CREATED".equals(entry.get("state"))) {
      return;
    }
    String kind = entry.get("kind").toString();
    String suffix = "";
    if (kind.equals("FUNCTION") && "postgresql".equalsIgnoreCase(dialect)) {
      suffix = switch (logical) {
        case "add_numbers", "multiply_numbers" -> "(INTEGER, INTEGER)";
        case "get_name_by_id" -> "(INTEGER)";
        case "update_name_by_id" -> "(INTEGER, VARCHAR)";
        case "get_count" -> "()";
        default -> throw new SQLException("Unknown owned function signature");
      };
    }
    if (kind.equals("TABLE") && "oracle".equalsIgnoreCase(dialect)) {
      suffix = " PURGE";
    }
    String command = "DROP " + kind + " " + qualified(logical) + suffix;
    if (kind.equals("INDEX") && ("mysql".equalsIgnoreCase(dialect)
        || "sqlserver".equalsIgnoreCase(dialect))) {
      command =
          "DROP INDEX " + quote(name(logical)) + " ON " + qualified(entry.get("parent").toString());
    }
    entry.put("state", "DROPPING");
    persist();
    try (Statement statement = connection.createStatement()) {
      statement.execute(command);
      entry.put("state", "DROPPED");
      persist();
    } catch (SQLException exception) {
      entry.put("state", "UNKNOWN");
      entry.put("sqlState", Objects.toString(exception.getSQLState(), ""));
      entry.put("vendorCode", exception.getErrorCode());
      persist();
      throw exception;
    }
  }

  public synchronized void cleanup(Connection connection) {
    List<String> keys = new ArrayList<>(journal.keySet());
    Collections.reverse(keys);
    for (String logical : keys) {
      try {
        drop(connection, logical);
      } catch (SQLException exception) {
        System.err.println("[JDBC_CLEANUP_ISSUE] owned object retained: " + qualified(logical));
      }
    }
  }

  public synchronized List<Map<String, Object>> snapshot() {
    return java.util.stream.Stream.concat(history.stream(), journal.values().stream())
        .sorted(Comparator.comparingInt(entry -> (Integer) entry.get("attempt"))).map(Map::copyOf)
        .toList();
  }

  private void persist() {
    if (ledgerPath == null || ledgerPath.isBlank()) {
      throw new IllegalStateException("Object ledger path is required");
    }
    Path path = Path.of(ledgerPath);
    try {
      Files.createDirectories(path.toAbsolutePath().getParent());
      Path temp = path.resolveSibling(path.getFileName() + ".tmp");
      Files.writeString(temp, new Yaml().dump(Map.of("objectPrefix", objectPrefix, "namespace",
              Map.of("name", namespace, "state", namespaceState), "objects", snapshot())),
          StandardCharsets.UTF_8);
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot persist object ownership ledger", exception);
    }
  }
}
