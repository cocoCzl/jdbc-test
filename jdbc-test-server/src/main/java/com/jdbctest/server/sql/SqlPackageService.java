package com.jdbctest.server.sql;

import com.jdbctest.catalog.SqlTestPackage;
import com.jdbctest.server.connection.ConnectionException;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@Service
public class SqlPackageService {

    private final SqlPackageRepository packages;
    private final ObjectMapper mapper;

    public SqlPackageService(SqlPackageRepository packages, ObjectMapper mapper) {
        this.packages = packages;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<Response> list() {
        return packages.findAll().stream().map(row -> response(decode(row))).toList();
    }

    @Transactional(readOnly = true)
    public SqlTestPackage required(String fingerprint) {
        return decode(packages.findById(fingerprint).orElseThrow(() -> new ConnectionException(
            HttpStatus.NOT_FOUND, "SQL_PACKAGE_NOT_FOUND", "SQL test package was not found")));
    }

    @Transactional
    public Response importPackage(MultipartFile file) {
        if (file.getSize() > 8L * 1_048_576) {
            throw invalid("archive exceeds 8 MiB");
        }
        try (var input = file.getInputStream()) {
            var snapshot = SqlTestPackage.readPackage(input);
            if (!packages.existsById(snapshot.fingerprint())) {
                packages.saveAndFlush(new SqlPackageEntity(snapshot.fingerprint(), mapper.writeValueAsString(snapshot)));
            }
            return response(snapshot);
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid(exception instanceof IOException ? "unreadable package" : exception.getMessage());
        }
    }

    private SqlTestPackage decode(SqlPackageEntity entity) {
        return mapper.readValue(entity.getSnapshotJson(), SqlTestPackage.class);
    }

    private Response response(SqlTestPackage snapshot) {
        return new Response(snapshot.fingerprint(), "LOCAL", snapshot.definition());
    }

    private ConnectionException invalid(String detail) {
        return new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT, "SQL_PACKAGE_INVALID", detail);
    }

    public record Response(String fingerprint, String origin, SqlTestPackage.Definition definition) {
    }
}
