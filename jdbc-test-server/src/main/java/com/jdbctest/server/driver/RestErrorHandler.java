package com.jdbctest.server.driver;

import com.jdbctest.server.connection.ConnectionException;
import com.jdbctest.server.run.RunException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@RestControllerAdvice
final class RestErrorHandler {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(
        RestErrorHandler.class);

    @ExceptionHandler(DriverException.class)
    ResponseEntity<ErrorResponse> driver(DriverException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage(),
            exception.properties());
    }

    @ExceptionHandler(ConnectionException.class)
    ResponseEntity<ErrorResponse> connection(ConnectionException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage(),
            exception.properties());
    }

    @ExceptionHandler(RunException.class)
    ResponseEntity<ErrorResponse> run(RunException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage(),
            java.util.Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "DRIVER_ARTIFACT_TOO_LARGE",
            "Upload exceeds configured size limit", java.util.Map.of());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request",
            java.util.Map.of());
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void disconnected(AsyncRequestNotUsableException exception) {
        // The async response is already unusable. Writing JSON into an SSE stream fails again.
        LOG.debug("Async response disconnected; no response body can be written");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> unsupportedMethod(Exception exception) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
            "This endpoint does not support this operation", java.util.Map.of());
    }

    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
        org.springframework.web.servlet.NoHandlerFoundException.class})
    ResponseEntity<ErrorResponse> missingEndpoint(Exception exception) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Endpoint was not found",
            java.util.Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internal(Exception exception) {
        if (org.springframework.web.util.DisconnectedClientHelper
            .isClientDisconnectedException(exception)) {
            // Servlet containers can wrap a broken pipe in a message-conversion exception.
            LOG.debug("Client disconnected; no response body can be written");
            return null;
        }
        LOG.error("Unexpected REST error ({})", exception.getClass().getName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Unexpected server error",
            java.util.Map.of());
    }

    private ResponseEntity<ErrorResponse> problem(HttpStatus status, String code, String detail,
        java.util.Map<String, Object> properties) {
        return ResponseEntity.status(status).body(
            new ErrorResponse("urn:jdbc-test:error:" + code, status.getReasonPhrase(),
                status.value(),
                detail, code, properties));
    }

    record ErrorResponse(String type, String title, int status, String detail, String code,
        java.util.Map<String, Object> properties) {

    }
}
