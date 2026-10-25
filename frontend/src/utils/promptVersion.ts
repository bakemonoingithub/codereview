/** Formats an actual prompt content version number, distinct from config revision. */
export function formatPromptVersion(versionNo: number | null | undefined): string {
  return Number.isInteger(versionNo) && Number(versionNo) > 0 ? `v${versionNo}` : '—'
}
