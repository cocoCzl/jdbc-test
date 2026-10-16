package com.jdbctest.server.connection;

import com.jdbctest.catalog.AdapterRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/adapters")
final class AdapterController {

    @GetMapping
    List<AdapterResponse> list() {
        return AdapterRegistry.bundled().list().stream().map(AdapterResponse::from).toList();
    }
}
