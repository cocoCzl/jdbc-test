package com.jdbctest.catalog;

import java.util.Objects;

/** A stable, API-friendly grouping of related JDBC scenarios. */
public record TestModule(String id, String displayName, String description) {
    public TestModule {
        requireText(id, "id");
        requireText(displayName, "displayName");
        requireText(description, "description");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
