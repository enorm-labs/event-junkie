/** The message of a thrown [e], which need not be an `Error`. */
export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
