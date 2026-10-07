package com.jdbctest.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UseSqlScripts {
    String[] ddl() default {};
    String[] dml() default {};
    String[] cleanup() default {};
}
