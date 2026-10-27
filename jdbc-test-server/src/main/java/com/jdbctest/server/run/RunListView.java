package com.jdbctest.server.run;

/** Closed projection: full execution input and per-case evidence never enter list SQL. */
public interface RunListView extends TestRunView {

    String getExecutionSummary();

}
