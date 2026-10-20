package com.jdbctest.server.connection;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/adapters")
final class AdapterController {

    private final AdapterService service;

    AdapterController(AdapterService service) {
        this.service = service;
    }

    @GetMapping
    List<AdapterResponse> list() {
        return service.list();
    }
}
