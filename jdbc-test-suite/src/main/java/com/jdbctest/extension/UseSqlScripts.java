package com.jdbctest.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface UseSqlScripts {

    String[] ddl() default {};

    String[] dml() default {};

    String[] cleanup() default {};

    /** Let a scenario observe an independent API before requesting its storage fixture. */
    boolean deferred() default false;

    /** Fixture shape checks run before the JDBC contract, using managed object placeholders. */
    String[] checks() default {};
}
