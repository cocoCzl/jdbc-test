package com.jdbctest.server.run;

import java.util.List;

public record RunReportResponse(TestRunResponse summary, List<TestCaseResultResponse> failures) {

}
