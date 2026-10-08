// Display-only masking, following the server's credential-key convention.
// This does not parse a JDBC URL, validate it, or change the saved connection.
export function redactJdbcUrl(value: string) {
  return value
    .replace(/((?:password|passwd|pwd|secret|token|access_token|user(?:name)?)\s*[:=]\s*)(?:\{[^}]*\}|"[^"]*"|'[^']*'|[^&;,#\s)]*)/gi, "$1[REDACTED]")
    .replace(/(\/\/)[^/@\s]+@/g, "$1[REDACTED]@")
    .replace(/(jdbc:oracle:[^:]+:)[^@\s]+\/@/gi, "$1[REDACTED]@")
    .replace(/(jdbc:oracle:[^:]+:)[^/@\s]+\/[^@\s]+@/gi, "$1[REDACTED]@");
}
