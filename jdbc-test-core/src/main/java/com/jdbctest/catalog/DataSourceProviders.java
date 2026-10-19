package com.jdbctest.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit implementations supplied by the tested driver package, never platform facades.
 */
public record DataSourceProviders(Provider dataSource, Provider connectionPoolDataSource,
    Provider xaDataSource) {

    public record Provider(String className, String urlProperty, String userProperty,
        String passwordProperty) {

        public Provider {
            if (className == null || className.length() > 300
                || !className.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
                + "(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*")) {
                throw new IllegalArgumentException("Invalid DataSource implementation class name");
            }
            urlProperty = property(urlProperty, "URL");
            userProperty = property(userProperty, "user");
            passwordProperty = property(passwordProperty, "password");
            if (urlProperty.equalsIgnoreCase(userProperty)
                || urlProperty.equalsIgnoreCase(passwordProperty)
                || userProperty.equalsIgnoreCase(passwordProperty)) {
                throw new IllegalArgumentException(
                    "DataSource credential property names must differ");
            }
        }

        private static String property(String value, String fallback) {
            String result = value == null || value.isBlank() ? fallback : value;
            if (result.length() > 100 || !result.matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid DataSource bean property name");
            }
            return result;
        }

        public Map<String, String> toMap() {
            return Map.of("className", className, "urlProperty", urlProperty,
                "userProperty", userProperty, "passwordProperty", passwordProperty);
        }
    }

    public static DataSourceProviders empty() {
        return new DataSourceProviders(null, null, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (dataSource != null) {
            result.put("dataSource", dataSource.toMap());
        }
        if (connectionPoolDataSource != null) {
            result.put("connectionPoolDataSource", connectionPoolDataSource.toMap());
        }
        if (xaDataSource != null) {
            result.put("xaDataSource", xaDataSource.toMap());
        }
        return result;
    }

    public static DataSourceProviders fromMap(Object value) {
        if (value == null) {
            return empty();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("db.data_sources must be a mapping");
        }
        if (!java.util.Set.of("dataSource", "connectionPoolDataSource", "xaDataSource")
            .containsAll(map.keySet())) {
            throw new IllegalArgumentException("Unknown DataSource provider interface");
        }
        return new DataSourceProviders(parse(map.get("dataSource")),
            parse(map.get("connectionPoolDataSource")), parse(map.get("xaDataSource")));
    }

    private static Provider parse(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)
            || !java.util.Set.of("className", "urlProperty", "userProperty", "passwordProperty")
            .containsAll(map.keySet())
            || map.values().stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("Invalid DataSource provider configuration");
        }
        return new Provider((String) map.get("className"), (String) map.get("urlProperty"),
            (String) map.get("userProperty"), (String) map.get("passwordProperty"));
    }

    /**
     * Provider presence is an execution prerequisite, not proof of JDBC API support.
     */
    public Map<String, Boolean> capabilities(Map<String, Boolean> adapter) {
        Map<String, Boolean> result = new LinkedHashMap<>(adapter);
        result.put("data_source", dataSource != null);
        result.put("connection_pool", connectionPoolDataSource != null);
        result.put("xa_data_source", xaDataSource != null);
        return Map.copyOf(result);
    }
}
