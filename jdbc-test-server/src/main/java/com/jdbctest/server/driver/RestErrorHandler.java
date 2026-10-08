package com.jdbctest.server.driver;

import com.jdbctest.server.connection.ConnectionException;
import com.jdbctest.server.run.RunException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> internal(Exception exception) {
    LOG.error("Unexpected REST error", exception);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error",
        java.util.Map.of());
  }

  private ResponseEntity<ErrorResponse> problem(HttpStatus status, String code, String detail,
      java.util.Map<String, Object> properties) {
    return ResponseEntity.status(status).body(
        new ErrorResponse("urn:jdbc-test:error:" + code, status.getReasonPhrase(), status.value(),
            detail, code, properties));
  }

  record ErrorResponse(String type, String title, int status, String detail, String code,
      java.util.Map<String, Object> properties) {

  }
}
