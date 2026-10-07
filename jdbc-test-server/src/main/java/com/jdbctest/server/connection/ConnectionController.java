package com.jdbctest.server.connection;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/connections")
final class ConnectionController {
    private final ConnectionService service;
    ConnectionController(ConnectionService service) { this.service = service; }

    @PostMapping ResponseEntity<ConnectionResponse> create(@Valid @RequestBody CreateConnectionRequest request) {
        ConnectionResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/connections/" + response.id())).body(response);
    }
    @GetMapping ConnectionPageResponse list(@RequestParam(value = "page", defaultValue = "0") int page,
                                            @RequestParam(value = "size", defaultValue = "20") int size,
                                            @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort) { return service.list(page, size, sort); }
    @GetMapping("/{id}") ConnectionResponse get(@PathVariable("id") UUID id) { return service.get(id); }
    @PatchMapping("/{id}") ConnectionResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateConnectionRequest request) { return service.update(id, request); }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable("id") UUID id) { service.delete(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/test") ConnectionProbeResponse testDraft(@Valid @RequestBody DraftConnectionTestRequest request) { return service.testDraft(request); }
    @PostMapping("/{id}/test") ConnectionProbeResponse testSaved(@PathVariable("id") UUID id, @Valid @RequestBody(required = false) ConnectionTestRequest request) { return service.testSaved(id, request); }
}
