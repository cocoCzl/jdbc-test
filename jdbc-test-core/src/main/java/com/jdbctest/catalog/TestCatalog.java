package com.jdbctest.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

/**
 * Catalog backed by the repository's versioned scenario inventory.
 *
 * The inventory remains the stable report metadata source; source files are checked as part of
 * discovery so a catalog cannot silently drift from tests.
 */
public final class TestCatalog {

    private static final String SCENARIOS_KEY = "scenarios";
    private static final Pattern FEATURE_ANNOTATION = Pattern.compile(
        "@RequiresFeature\\s*\\(([^)]*)\\)");
    private static final Pattern QUOTED_VALUE = Pattern.compile("\\\"([^\\\"]+)\\\"");
    private static final Map<String, ModuleDefinition> MODULES = moduleDefinitions();

    private final List<TestCaseDescriptor> descriptors;
    private final Map<String, TestCaseDescriptor> byScenarioId;
    private final Map<String, TestModule> modules;

    private TestCatalog(List<TestCaseDescriptor> descriptors) {
        this.descriptors = List.copyOf(descriptors);
        this.byScenarioId = Map.copyOf(descriptors.stream().collect(Collectors.toMap(
            TestCaseDescriptor::scenarioId,
            Function.identity(),
            (left, right) -> {
                throw new IllegalArgumentException("Duplicate scenario ID: " + left.scenarioId());
            },
            LinkedHashMap::new
        )));
        this.modules = Map.copyOf(
            descriptors.stream().map(TestCaseDescriptor::module).collect(Collectors.toMap(
                TestModule::id,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
            )));
    }

    public static TestCatalog fromScenarioInventory(Path inventoryPath, Path sourceRoot) {
        Objects.requireNonNull(inventoryPath, "inventoryPath");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        if (!Files.isRegularFile(inventoryPath)) {
            throw new IllegalArgumentException(
                "Scenario inventory does not exist: " + inventoryPath);
        }
        try (InputStream input = Files.newInputStream(inventoryPath)) {
            Object loaded = new Yaml().load(input);
            return fromYaml(loaded, sourceRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read scenario inventory: " + inventoryPath,
                exception);
        }
    }

    /**
     * Load the packaged catalog without requiring repository source files.
     */
    public static TestCatalog loadDefault() {
        InputStream input = TestCatalog.class.getResourceAsStream(
            "/compatibility/v1/scenario-inventory.yaml");
        if (input == null) {
            throw new IllegalStateException("Packaged scenario inventory is missing");
        }
        try (input) {
            return fromYaml(new Yaml().load(input), null);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read packaged scenario inventory",
                exception);
        }
    }

    public static TestCatalog fromYaml(Object yaml, Path sourceRoot) {
        Objects.requireNonNull(yaml, "yaml");
        Map<?, ?> root = asMap(yaml, "root");
        List<?> rows = asList(root.get(SCENARIOS_KEY), SCENARIOS_KEY);
        List<TestCaseDescriptor> descriptors = new ArrayList<>();
        for (Object row : rows) {
            Map<?, ?> item = asMap(row, "scenario");
            String scenarioId = text(item, "scenario_id");
            String sourceClass = text(item, "source_class");
            String sourceMethod = text(item, "source_method");
            SourceMetadata sourceMetadata = sourceRoot == null
                ? readRuntimeMetadata(sourceClass, sourceMethod)
                : readSourceMetadata(sourceRoot, sourceClass, sourceMethod);
            TestModule module = moduleFor(scenarioId);
            descriptors.add(new TestCaseDescriptor(
                scenarioId,
                module,
                sourceMetadata.requiredCapabilities().isEmpty() ? TestProfile.CORE
                    : TestProfile.FULL,
                text(item, "contract"),
                sourceClass,
                sourceMethod,
                sourceMetadata.requiredCapabilities()
            ));
        }
        descriptors.sort(Comparator.comparing(TestCaseDescriptor::scenarioId));
        return new TestCatalog(descriptors);
    }

    public List<TestCaseDescriptor> testCases() {
        return descriptors;
    }

    public List<TestModule> modules() {
        return List.copyOf(modules.values());
    }

    public List<TestCaseDescriptor> select(TestSelection selection) {
        Objects.requireNonNull(selection, "selection");
        validateNames(selection);
        return descriptors.stream()
            .filter(
                item -> selection.profile() == TestProfile.FULL
                    || item.profile() == TestProfile.CORE)
            .filter(item -> selection.includedModules().isEmpty()
                || selection.includedModules().contains(item.module().id()))
            .filter(item -> selection.includedScenarios().isEmpty()
                || selection.includedScenarios().contains(item.scenarioId()))
            .filter(item -> !selection.excludedScenarios().contains(item.scenarioId()))
            .toList();
    }

    private void validateNames(TestSelection selection) {
        for (String module : selection.includedModules()) {
            if (!modules.containsKey(module)) {
                throw new IllegalArgumentException("Unknown test module: " + module);
            }
        }
        validateScenarioNames(selection.includedScenarios());
        validateScenarioNames(selection.excludedScenarios());
    }

    private void validateScenarioNames(Set<String> scenarioIds) {
        for (String scenarioId : scenarioIds) {
            if (!byScenarioId.containsKey(scenarioId)) {
                throw new IllegalArgumentException("Unknown scenario: " + scenarioId);
            }
        }
    }

    private static SourceMetadata readSourceMetadata(Path sourceRoot, String className,
        String methodName) {
        String relativeClass = className.replace('.', '/') + ".java";
        Path sourceFile = sourceRoot.resolve(relativeClass);
        if (!Files.isRegularFile(sourceFile)) {
            throw new IllegalArgumentException(
                "Scenario source class does not exist: " + className);
        }
        try {
            String source = Files.readString(sourceFile);
            if (!source.contains(methodName + "(")) {
                throw new IllegalArgumentException(
                    "Scenario source method does not exist: " + className + "#" + methodName);
            }
            int classPosition = source.indexOf(
                "class " + className.substring(className.lastIndexOf('.') + 1));
            int methodPosition = source.indexOf(methodName + "(", classPosition);
            String classAnnotations = source.substring(0, classPosition);
            // Array-valued annotations contain '}' too; only a standalone closing brace ends a block.
            Matcher blockEnd = Pattern.compile("(?m)^[ \\t]*}[ \\t]*$").matcher(source);
            blockEnd.region(classPosition, methodPosition);
            int previousMethodEnd = classPosition;
            while (blockEnd.find()) {
                previousMethodEnd = blockEnd.end();
            }
            String methodAnnotations = source.substring(previousMethodEnd, methodPosition);
            LinkedHashSet<String> capabilities = new LinkedHashSet<>();
            capabilities.addAll(annotationValues(classAnnotations));
            capabilities.addAll(annotationValues(methodAnnotations));
            return new SourceMetadata(List.copyOf(capabilities));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read scenario source: " + sourceFile,
                exception);
        }
    }

    private static SourceMetadata readRuntimeMetadata(String className, String methodName) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> testClass = Class.forName(className, false, loader);
            LinkedHashSet<String> capabilities = new LinkedHashSet<>();
            capabilities.addAll(readFeatureAnnotation(testClass));
            var method = java.util.Arrays.stream(testClass.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(methodName));
            capabilities.addAll(readFeatureAnnotation(method));
            return new SourceMetadata(List.copyOf(capabilities));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(
                "Packaged scenario source is unavailable: " + className + "#" + methodName,
                exception);
        }
    }

    private static List<String> readFeatureAnnotation(java.lang.reflect.AnnotatedElement element) {
        for (var annotation : element.getDeclaredAnnotations()) {
            if (!annotation.annotationType().getName()
                .equals("com.jdbctest.extension.RequiresFeature")) {
                continue;
            }
            try {
                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                if (value instanceof String[] features) {
                    return List.of(features);
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to read RequiresFeature metadata",
                    exception);
            }
        }
        return List.of();
    }

    private static List<String> annotationValues(String source) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher annotation = FEATURE_ANNOTATION.matcher(source);
        while (annotation.find()) {
            Matcher quoted = QUOTED_VALUE.matcher(annotation.group(1));
            while (quoted.find()) {
                values.add(quoted.group(1));
            }
        }
        return List.copyOf(values);
    }

    private static TestModule moduleFor(String scenarioId) {
        int separator = scenarioId.indexOf('.');
        if (separator <= 0) {
            throw new IllegalArgumentException(
                "Scenario ID must contain a module prefix: " + scenarioId);
        }
        String moduleId = toKebabCase(scenarioId.substring(0, separator));
        ModuleDefinition definition = MODULES.get(moduleId);
        if (definition == null) {
            throw new IllegalArgumentException("No module definition for scenario: " + scenarioId);
        }
        return new TestModule(moduleId, definition.displayName(), definition.description());
    }

    private static Map<String, ModuleDefinition> moduleDefinitions() {
        Map<String, ModuleDefinition> definitions = new LinkedHashMap<>();
        definitions.put("advanced-type",
            new ModuleDefinition("Advanced Type", "JDBC advanced type compatibility tests"));
        definitions.put("blob-clob",
            new ModuleDefinition("LOB", "JDBC Blob and Clob compatibility tests"));
        definitions.put("callable-statement",
            new ModuleDefinition("CallableStatement",
                "JDBC CallableStatement compatibility tests"));
        definitions.put("connection",
            new ModuleDefinition("Connection", "JDBC Connection compatibility tests"));
        definitions.put("database-metadata",
            new ModuleDefinition("DatabaseMetaData", "JDBC DatabaseMetaData compatibility tests"));
        definitions.put("datasource",
            new ModuleDefinition("DataSource", "JDBC DataSource compatibility tests"));
        definitions.put("jdbc-type",
            new ModuleDefinition("JDBC Types", "JDBC type metadata compatibility tests"));
        definitions.put("lifecycle",
            new ModuleDefinition("Lifecycle", "JDBC resource lifecycle compatibility tests"));
        definitions.put("parameter-metadata",
            new ModuleDefinition("ParameterMetaData",
                "JDBC ParameterMetaData compatibility tests"));
        definitions.put("prepared-statement",
            new ModuleDefinition("PreparedStatement",
                "JDBC PreparedStatement compatibility tests"));
        definitions.put("result-set",
            new ModuleDefinition("ResultSet", "JDBC ResultSet compatibility tests"));
        definitions.put("result-set-metadata",
            new ModuleDefinition("ResultSetMetaData",
                "JDBC ResultSetMetaData compatibility tests"));
        definitions.put("rowset",
            new ModuleDefinition("RowSet", "JDBC RowSet compatibility tests"));
        definitions.put("savepoint",
            new ModuleDefinition("Savepoint", "JDBC Savepoint compatibility tests"));
        definitions.put("sqlxml",
            new ModuleDefinition("SQLXML", "JDBC SQLXML compatibility tests"));
        definitions.put("statement",
            new ModuleDefinition("Statement", "JDBC Statement compatibility tests"));
        definitions.put("wrapper",
            new ModuleDefinition("Wrapper", "JDBC Wrapper compatibility tests"));
        return Map.copyOf(definitions);
    }

    private static String toKebabCase(String value) {
        return value.replace('_', '-');
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Scenario field must be non-blank: " + key);
        }
        return text;
    }

    private static Map<?, ?> asMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " must be a map");
        }
        return map;
    }

    private static List<?> asList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be a list");
        }
        return list;
    }

    private record ModuleDefinition(String displayName, String description) {

    }

    private record SourceMetadata(List<String> requiredCapabilities) {

    }
}
