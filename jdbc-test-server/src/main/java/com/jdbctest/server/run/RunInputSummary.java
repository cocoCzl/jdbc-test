package com.jdbctest.server.run;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Additive list metadata; original frozen inputs remain the execution/report authority. */
public final class RunInputSummary {

    private RunInputSummary() {
    }

    public static JsonNode compact(JsonNode input) {
        if (!(input instanceof ObjectNode object)) {
            return input;
        }
        ObjectNode summary = object.deepCopy();
        summary.retain("scope", "objectPrefix", "adapterSnapshot", "adapterExecution", "sqlPackage",
            "selection", "budgetMs", "caseTimeoutMs");
        if (summary.get("selection") instanceof ObjectNode selection) {
            selection.retain("catalogVersion", "catalogCount");
        }
        return summary;
    }
}
