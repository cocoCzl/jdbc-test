package com.jdbctest.server.sql;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/sql-packages")
final class SqlPackageController {

    private final SqlPackageService service;

    SqlPackageController(SqlPackageService service) {
        this.service = service;
    }

    @GetMapping
    List<SqlPackageService.Response> list() {
        return service.list();
    }

    @PostMapping(consumes = "multipart/form-data")
    SqlPackageService.Response importPackage(@RequestPart("file") MultipartFile file) {
        return service.importPackage(file);
    }
}
