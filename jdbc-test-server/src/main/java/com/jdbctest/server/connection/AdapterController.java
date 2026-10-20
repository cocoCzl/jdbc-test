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

    @org.springframework.web.bind.annotation.PostMapping(consumes = "multipart/form-data")
    AdapterResponse importPackage(
        @org.springframework.web.bind.annotation.RequestPart("file")
        org.springframework.web.multipart.MultipartFile file,
        @org.springframework.web.bind.annotation.RequestParam(value = "expectedFingerprint",
            required = false) String expectedFingerprint) {
        return service.importPackage(file, expectedFingerprint);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    org.springframework.http.ResponseEntity<Void> archive(
        @org.springframework.web.bind.annotation.PathVariable("id") String id) {
        service.archive(id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping
    List<AdapterResponse> list() {
        return service.list();
    }
}
