export function validateFiles(files: File[]): string | null {
  if (!files.length) return "Choose at least one JAR file.";
  if (files.length > 20) return "A package can contain at most 20 artifacts.";
  if (files.some((file) => !file.name.toLowerCase().endsWith(".jar")))
    return "Only .jar files are accepted.";
  if (files.some((file) => file.size > 100 * 1024 ** 2))
    return "Each artifact must be 100 MB or smaller.";
  if (files.reduce((total, file) => total + file.size, 0) > 250 * 1024 ** 2)
    return "The package must be 250 MB or smaller.";
  return null;
}
