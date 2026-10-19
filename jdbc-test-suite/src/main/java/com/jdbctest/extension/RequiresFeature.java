package com.jdbctest.extension;

import java.lang.annotation.*;

/** Describes tested capabilities; only Provider configuration is an execution prerequisite. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresFeature {

    String[] value();
}
