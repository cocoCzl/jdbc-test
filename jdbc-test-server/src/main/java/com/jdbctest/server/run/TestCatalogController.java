package com.jdbctest.server.run;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test-catalog")
final class TestCatalogController {

    private final RunCatalogService catalog;

    TestCatalogController(RunCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    CatalogResponse get(@RequestParam(name = "adapterId", required = false)
        String adapterId) {
        if (adapterId != null && !com.jdbctest.catalog.AdapterRegistry.bundled().contains(adapterId)) {
            throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                "INVALID_ADAPTER", "Unknown adapter");
        }
        var source = catalog.catalog();
        var scenarios = source.scenarios().stream()
            .filter(item -> item.selectionEntry().appliesTo(adapterId)).toList();
        var modules = source.modules().stream().map(item -> new ModuleResponse(item.id(),
            item.name(), item.description(), (int) scenarios.stream()
                .filter(row -> row.moduleId().equals(item.id())).count(),
            (int) scenarios.stream().filter(row -> row.moduleId().equals(item.id())
                && "CORE".equals(row.profile())).count())).filter(item -> item.scenarioCount() > 0)
            .toList();
        return new CatalogResponse(List.of(
            new ProfileResponse("CORE", "Core", (int) scenarios.stream()
                .filter(item -> "CORE".equals(item.profile())).count()),
            new ProfileResponse("FULL", "Full", scenarios.size())), modules, scenarios.size(),
            scenarios, source.catalogVersion());
    }

    record CatalogResponse(List<ProfileResponse> profiles, List<ModuleResponse> modules,
        int totalScenarioCount, List<RunCatalogService.Scenario> scenarios, String catalogVersion) {

    }

    record ProfileResponse(String id, String name, int scenarioCount) {

    }

    record ModuleResponse(String id, String name, String description, int scenarioCount,
        int coreScenarioCount) {

    }
}
