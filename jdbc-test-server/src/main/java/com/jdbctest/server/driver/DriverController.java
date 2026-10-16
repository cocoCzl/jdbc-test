package com.jdbctest.server.driver;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/drivers")
final class DriverController {

    private final DriverService service;

    DriverController(DriverService service) {
        this.service = service;
    }

    @PostMapping(consumes = "multipart/form-data")
    ResponseEntity<DriverResponse> create(
        @Valid @RequestPart("metadata") CreateDriverRequest metadata,
        @RequestPart("files") List<MultipartFile> files) {
        DriverResponse result = service.create(metadata, files);
        return ResponseEntity.created(URI.create("/api/drivers/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    DriverResponse get(@PathVariable("id") UUID id) {
        return service.get(id);
    }

    @GetMapping
    DriverPageResponse list(@RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size,
        @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort) {
        return service.list(page, size, sort);
    }

    @PatchMapping("/{id}")
    DriverResponse update(@PathVariable("id") UUID id,
        @Valid @org.springframework.web.bind.annotation.RequestBody UpdateDriverRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/validate")
    DriverValidationResponse validate(@PathVariable("id") UUID id) {
        return service.validate(id);
    }
}
