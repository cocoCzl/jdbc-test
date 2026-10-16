package com.jdbctest.catalog;

import java.util.Arrays;
import java.util.Optional;

/**
 * Known product classifications, independent of adapter registration and SQL behavior.
 */
public enum DbType {
    GAUSSDB("gaussdb"),
    KINGBASE("kingbase"),
    MYSQL("mysql"),
    ORACLE("oracle"),
    POSTGRESQL("postgresql"),
    SQL_SERVER("sqlserver");

    private final String code;

    DbType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<DbType> fromCode(String code) {
        return Arrays.stream(values()).filter(type -> type.code.equals(code)).findFirst();
    }
}
