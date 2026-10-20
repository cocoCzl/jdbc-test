package com.jdbctest.server.connection;

import com.jdbctest.catalog.AdapterDefinition;
import com.jdbctest.catalog.AdapterRegistry;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Only source-maintained, bundled adapters can prepare new JDBC runs. */
@Service
public class AdapterService {

    public List<AdapterResponse> list() {
        return AdapterRegistry.bundled().list().stream().map(AdapterResponse::from).toList();
    }

    public AdapterDefinition required(String id) {
        if (id == null || !AdapterRegistry.bundled().contains(id)) {
            throw new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT,
                "BUILTIN_ADAPTER_REQUIRED",
                "Select a supported built-in database type or compatibility dialect; "
                    + "uploaded adapters are retired and cannot prepare new runs");
        }
        return AdapterRegistry.bundled().required(id);
    }
}
