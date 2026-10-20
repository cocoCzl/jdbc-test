package com.jdbctest.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Version 1 SQL feature definitions. No Java code, script splitting or implicit dialect fallback. */
public record SqlTestPackage(String manifest, String fingerprint) {

    private static final Set<String> TYPES = Set.of("INTEGER", "DECIMAL", "STRING", "BOOLEAN");

    public SqlTestPackage {
        parse(manifest);
        String actual;
        try {
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        if (fingerprint != null && !fingerprint.equals(actual)) {
            throw invalid("fingerprint", "content mismatch");
        }
        fingerprint = actual;
    }

    public static SqlTestPackage read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(1_048_577);
        AdapterJson.read(new ByteArrayInputStream(bytes));
        return new SqlTestPackage(new String(bytes, StandardCharsets.UTF_8), null);
    }

    /** Content-based format selection shared by Web and CLI, independent of upload filename. */
    public static SqlTestPackage readPackage(InputStream input) throws IOException {
        var buffered = new java.io.PushbackInputStream(input, 2);
        byte[] header = buffered.readNBytes(2);
        buffered.unread(header);
        return header.length == 2 && header[0] == 'P' && header[1] == 'K'
            ? readZip(buffered) : read(buffered);
    }

    public static SqlTestPackage readZip(InputStream input) throws IOException {
        var files = DataPackageArchive.read(input);
        if (!files.keySet().equals(Set.of("sql-package.json"))) {
            throw invalid("archive", "only root sql-package.json is supported in v1");
        }
        return read(new ByteArrayInputStream(Base64.getDecoder().decode(files.get("sql-package.json"))));
    }

    public Definition definition() {
        return parse(manifest);
    }

    public record Definition(String id, String version, String description, List<SqlCase> cases) {
    }

    public record SqlCase(String id, String name, List<String> tags, Applies appliesTo,
        int timeoutSeconds, List<Step> prerequisites, Map<String, Variant> variants) {
        public TestCaseDescriptor descriptor() {
            return new TestCaseDescriptor(id, new TestModule("sql_feature", "SQL features",
                "Declarative SQL assertions"), TestProfile.CUSTOM, name,
                "declarative.SQL", id, List.of(), TestCategory.SQL_FEATURE, List.of(),
                "NOT_APPLICABLE", List.of(), "REQUIRED");
        }
    }

    public record Applies(List<String> products, List<String> dialects, int minMajor, int maxMajor,
        String modeDescription) {
    }

    public record Variant(List<Step> prepare, List<Step> execute, List<String> cleanup) {
    }

    public record Step(String id, String sql, List<Parameter> parameters, Expect expect) {
    }

    public record Parameter(String type, Object value) {
    }

    public record Expect(String kind, String order, List<String> columns,
        List<List<Object>> rows, String decimalMode, Long count, String sqlState,
        String exceptionType, Integer vendorCode) {
    }

    private static Definition parse(String manifest) {
        Object raw;
        try {
            raw = AdapterJson.read(new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            throw invalid("manifest", "invalid JSON");
        }
        var root = object(raw, "$", "formatVersion", "id", "version", "description", "cases");
        if (integer(root.get("formatVersion"), "formatVersion", 1, 1) != 1) {
            throw invalid("formatVersion", "expected 1");
        }
        List<SqlCase> cases = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (Object item : list(root.get("cases"), "cases", 1, 200)) {
            var row = object(item, "cases[]", "id", "name", "tags", "appliesTo", "timeoutSeconds",
                "prerequisites", "variants");
            String id = id(row.get("id"), "cases[].id");
            if (!ids.add(id)) {
                throw invalid("cases[].id", "duplicate " + id);
            }
            var applies = object(row.get("appliesTo"), id + ".appliesTo", "products", "dialects",
                "minMajor", "maxMajor", "modeDescription");
            List<String> dialects = strings(applies.get("dialects"), id + ".dialects", 1);
            dialects.forEach(SqlDialect::fromCode);
            int min = integer(applies.get("minMajor"), id + ".minMajor", 0, 9999);
            int max = integer(applies.get("maxMajor"), id + ".maxMajor", min, 9999);
            Applies applicability = new Applies(strings(applies.get("products"), id + ".products", 1),
                dialects, min, max, text(applies.get("modeDescription"), id + ".modeDescription"));
            Map<String, Variant> variants = new LinkedHashMap<>();
            var variantMap = object(row.get("variants"), id + ".variants", dialects.toArray(String[]::new));
            if (!variantMap.keySet().equals(Set.copyOf(dialects))) {
                throw invalid(id + ".variants", "must explicitly define every declared dialect");
            }
            for (var entry : variantMap.entrySet()) {
                var variant = object(entry.getValue(), id + "." + entry.getKey(), "prepare", "execute", "cleanup");
                List<Step> prepare = steps(variant.get("prepare"), id + ".prepare", false, true);
                List<Step> execute = steps(variant.get("execute"), id + ".execute", true, false);
                if (execute.isEmpty()) {
                    throw invalid(id + ".execute", "at least one execution step required");
                }
                List<String> cleanup = strings(variant.get("cleanup"), id + ".cleanup", 0);
                Set<String> created = new java.util.HashSet<>();
                for (Step step : prepare) {
                    var matcher = java.util.regex.Pattern.compile("(?is)^CREATE TABLE \\{\\{([a-z][a-z0-9_]*)}}.*").matcher(step.sql());
                    if (matcher.matches() && !created.add(matcher.group(1))) {
                        throw invalid(id + ".prepare", "duplicate owned table");
                    }
                }
                for (Step step : java.util.stream.Stream.concat(prepare.stream(), execute.stream()).toList()) {
                    var tokens = java.util.regex.Pattern.compile("\\{\\{([a-z][a-z0-9_]*)}}").matcher(step.sql());
                    while (tokens.find()) {
                        if (!created.contains(tokens.group(1))) {
                            throw invalid(id + ".sql", "placeholder has no prepared owned table: " + tokens.group(1));
                        }
                    }
                }
                if (!created.equals(Set.copyOf(cleanup)) || cleanup.size() != created.size()) {
                    throw invalid(id + ".cleanup", "must list each prepared table exactly once");
                }
                variants.put(entry.getKey(), new Variant(prepare, execute, cleanup));
            }
            cases.add(new SqlCase(id, text(row.get("name"), id + ".name"),
                strings(row.get("tags"), id + ".tags", 0), applicability,
                integer(row.get("timeoutSeconds"), id + ".timeoutSeconds", 1, 300),
                steps(row.get("prerequisites"), id + ".prerequisites", false, false), Map.copyOf(variants)));
        }
        return new Definition(id(root.get("id"), "id"), text(root.get("version"), "version"),
            text(root.get("description"), "description"), List.copyOf(cases));
    }

    private static List<Step> steps(Object raw, String path, boolean errors, boolean preparation) {
        List<Step> steps = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (Object item : list(raw, path, 0, 100)) {
            var row = object(item, path, "id", "sql", "parameters", "expect");
            String id = id(row.get("id"), path + ".id");
            if (!ids.add(id)) {
                throw invalid(path + ".id", "duplicate step");
            }
            String sql = text(row.get("sql"), path + ".sql");
            // Deliberately narrow v1 grammar: no comments, statement separators or session commands.
            // This is a format restriction, never a substitute for dedicated database permissions.
            if (sql.length() > 16384 || sql.contains(";") || sql.contains("--") || sql.contains("/*")
                || !sql.matches("(?s)^(SELECT |INSERT INTO |UPDATE |DELETE FROM |CREATE TABLE ).*")) {
                throw invalid(path + ".sql", "one SELECT/DML or prepared CREATE TABLE; no comments/semicolons");
            }
            if (sql.matches("(?s)^(INSERT INTO |UPDATE |DELETE FROM ).*")
                && !sql.matches("(?s)^(INSERT INTO |UPDATE |DELETE FROM )\\{\\{[a-z][a-z0-9_]*}} .*")) {
                throw invalid(path + ".sql", "DML target must be an owned table placeholder");
            }
            boolean create = sql.startsWith("CREATE TABLE ");
            if (create && (!preparation || !sql.matches("(?is)^CREATE TABLE \\{\\{[a-z][a-z0-9_]*}} \\(.*"))) {
                throw invalid(path + ".sql", "CREATE TABLE requires preparation and an owned placeholder");
            }
            List<Parameter> parameters = new ArrayList<>();
            for (Object parameter : list(row.get("parameters"), path + ".parameters", 0, 100)) {
                var parsed = object(parameter, path + ".parameters[]", "type", "value");
                String type = type(parsed.get("type"), path);
                if (!parsed.containsKey("value")) {
                    throw invalid(path, "parameter value required (explicit null allowed)");
                }
                Object value = canonical(type, parsed.get("value"), "SCALE");
                if ("INTEGER".equals(type) && value instanceof BigInteger number
                    && (number.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                    || number.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0)) {
                    throw invalid(path + ".parameters", "JDBC INTEGER parameter exceeds signed 32-bit range");
                }
                parameters.add(new Parameter(type, parsed.get("value")));
            }
            Expect expect = expect(row.get("expect"), path + ".expect");
            if ("ERROR".equals(expect.kind()) && !errors) {
                throw invalid(path + ".expect", "expected errors only allowed on execution steps");
            }
            if (!errors && !preparation && (!sql.startsWith("SELECT ") || !"ROWS".equals(expect.kind()))) {
                throw invalid(path, "prerequisites must be SELECT/ROWS");
            }
            if (create && (!parameters.isEmpty() || !"UPDATE".equals(expect.kind()))) {
                throw invalid(path, "CREATE uses UPDATE count and no parameters");
            }
            steps.add(new Step(id, sql, List.copyOf(parameters), expect));
        }
        return List.copyOf(steps);
    }

    private static Expect expect(Object raw, String path) {
        var row = object(raw, path, "kind", "order", "columns", "rows", "decimalMode", "count",
            "sqlState", "exceptionType", "vendorCode");
        String kind = text(row.get("kind"), path + ".kind");
        Set<String> allowed = switch (kind) {
            case "ROWS" -> Set.of("kind", "order", "columns", "rows", "decimalMode");
            case "UPDATE" -> Set.of("kind", "count");
            case "ERROR" -> Set.of("kind", "sqlState", "exceptionType", "vendorCode");
            default -> throw invalid(path + ".kind", "unsupported assertion");
        };
        if (!allowed.containsAll(row.keySet())) {
            throw invalid(path, "fields do not match assertion kind");
        }
        if ("UPDATE".equals(kind)) {
            return new Expect(kind, null, List.of(), List.of(), null,
                (long) integer(row.get("count"), path + ".count", 0, Integer.MAX_VALUE), null, null, null);
        }
        if ("ERROR".equals(kind)) {
            String state = row.containsKey("sqlState") ? text(row.get("sqlState"), path + ".sqlState") : null;
            Integer code = row.containsKey("vendorCode")
                ? integer(row.get("vendorCode"), path + ".vendorCode", Integer.MIN_VALUE, Integer.MAX_VALUE) : null;
            if (state == null && code == null || state != null && !state.matches("[A-Z0-9]{5}")) {
                throw invalid(path, "exact SQLState or vendorCode required");
            }
            if (state != null && infrastructureState(state)) {
                throw invalid(path, "connection, permission and cancellation errors cannot be expected");
            }
            String exception = row.containsKey("exceptionType") ? text(row.get("exceptionType"), path + ".exceptionType") : null;
            if (exception != null && !exception.matches("(?:java\\.sql|[A-Za-z_][A-Za-z0-9_.]*)\\.[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw invalid(path, "qualified exception class required");
            }
            return new Expect(kind, null, List.of(), List.of(), null, null, state, exception, code);
        }
        String order = text(row.get("order"), path + ".order");
        String decimal = text(row.get("decimalMode"), path + ".decimalMode");
        if (!Set.of("ORDERED", "MULTISET").contains(order) || !Set.of("NUMERIC", "SCALE").contains(decimal)) {
            throw invalid(path, "order=ORDERED|MULTISET and decimalMode=NUMERIC|SCALE required");
        }
        List<String> columns = strings(row.get("columns"), path + ".columns", 1);
        columns.forEach(column -> type(column, path));
        List<List<Object>> rows = new ArrayList<>();
        for (Object value : list(row.get("rows"), path + ".rows", 0, 1000)) {
            List<?> cells = list(value, path + ".rows[]", columns.size(), columns.size());
            List<Object> copy = new ArrayList<>(cells);
            for (int index = 0; index < columns.size(); index++) {
                canonical(columns.get(index), cells.get(index), decimal);
            }
            rows.add(java.util.Collections.unmodifiableList(copy));
        }
        return new Expect(kind, order, List.copyOf(columns), List.copyOf(rows), decimal, null, null, null, null);
    }

    public static boolean infrastructureState(String state) {
        return state != null && (state.startsWith("08") || state.startsWith("28")
            || Set.of("42501", "57014", "HYT00", "HYT01").contains(state));
    }

    /** Exact typed comparison; JSON null is the SQL NULL sentinel. */
    public static Object canonical(String type, Object value, String decimalMode) {
        if (value == null) {
            return null;
        }
        try {
            return switch (type) {
                case "STRING" -> {
                    if (!(value instanceof String)) {
                        throw invalid("value", "STRING requires JSON string");
                    }
                    yield value;
                }
                case "BOOLEAN" -> {
                    if (!(value instanceof Boolean)) {
                        throw invalid("value", "BOOLEAN requires JSON boolean");
                    }
                    yield value;
                }
                case "INTEGER", "DECIMAL" -> {
                    if (!(value instanceof String text) || !text.matches("-?[0-9]+(?:\\.[0-9]+)?")) {
                        throw invalid("value", "exact numbers require plain decimal JSON strings");
                    }
                    if ("INTEGER".equals(type)) {
                        yield new BigInteger((String) value);
                    }
                    BigDecimal decimal = new BigDecimal((String) value);
                    yield "NUMERIC".equals(decimalMode) ? decimal.stripTrailingZeros() : decimal;
                }
                default -> throw invalid("value.type", "unsupported type " + type);
            };
        } catch (NumberFormatException exception) {
            throw invalid("value", "invalid " + type);
        }
    }

    private static String type(Object raw, String path) {
        String value = text(raw, path + ".type");
        if (!TYPES.contains(value)) {
            throw invalid(path + ".type", "unsupported type " + value);
        }
        return value;
    }

    private static Map<String, Object> object(Object raw, String path, String... allowed) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid(path, "object required");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !Set.of(allowed).contains(key)) {
                throw invalid(path, "unknown field " + entry.getKey());
            }
            copy.put((String) entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private static List<?> list(Object raw, String path, int min, int max) {
        if (!(raw instanceof List<?> values) || values.size() < min || values.size() > max) {
            throw invalid(path, "array size must be " + min + ".." + max);
        }
        return values;
    }

    private static List<String> strings(Object raw, String path, int min) {
        return list(raw, path, min, 100).stream().map(value -> text(value, path)).toList();
    }

    private static String id(Object raw, String path) {
        String value = text(raw, path);
        if (!value.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw invalid(path, "portable stable ID required (max 64)");
        }
        return value;
    }

    private static String text(Object raw, String path) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 32768) {
            throw invalid(path, "nonempty string required");
        }
        return value;
    }

    private static int integer(Object raw, String path, int min, int max) {
        if (!(raw instanceof BigInteger value) || value.compareTo(BigInteger.valueOf(min)) < 0
            || value.compareTo(BigInteger.valueOf(max)) > 0) {
            throw invalid(path, "integer must be " + min + ".." + max);
        }
        return value.intValueExact();
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException("SQL_PACKAGE_INVALID " + path + ": " + reason);
    }
}
