package com.jdbctest.server.connection;

import com.jdbctest.catalog.AdapterCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/adapters")
final class AdapterController {

  @GetMapping
  List<AdapterCatalog.AdapterMetadata> list() {
    return AdapterCatalog.list();
  }
}
