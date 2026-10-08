export function validateFiles(files: File[]): string | null {
  if (!files.length) return "ui.chooseAtLeastOneJARFile";
  if (files.length > 20) return "ui.aPackageCanContainAtMost20";
  if (files.some((file) => !file.name.toLowerCase().endsWith(".jar")))
    return "ui.onlyJarFilesAreAccepted";
  if (files.some((file) => file.size > 100 * 1024 ** 2))
    return "ui.eachArtifactMustBe100MBOr";
  if (files.reduce((total, file) => total + file.size, 0) > 250 * 1024 ** 2)
    return "ui.thePackageMustBe250MBOr";
  return null;
}
