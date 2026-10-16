package com.jdbctest.server.run;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test-catalog")
final class TestCatalogController {

    private final RunCatalogService catalog;

    TestCatalogController(RunCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    CatalogResponse get() {
        var source = catalog.catalog();
        return new CatalogResponse(source.profiles().stream()
            .map(item -> new ProfileResponse(item.id(), item.name(), item.scenarioCount()))
            .toList(),
            source.modules().stream().map(
                item -> new ModuleResponse(item.id(), item.name(), item.description(),
                    item.scenarioCount(), item.coreScenarioCount())).toList(),
            source.totalScenarioCount());
    }

    record CatalogResponse(List<ProfileResponse> profiles, List<ModuleResponse> modules,
        int totalScenarioCount) {

    }

    record ProfileResponse(String id, String name, int scenarioCount) {

    }

    record ModuleResponse(String id, String name, String description, int scenarioCount,
        int coreScenarioCount) {

    }
}
