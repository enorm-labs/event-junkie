/**
 * The importer forward `scripts/ej.sh up <env>` opens, per cluster. Keep the ports in step with
 * `forward_spec` there. Imported by `vite.config.ts`, so nothing here may touch a browser global.
 */
export const IMPORTER_PORTS = {
  staging: 18081,
  production: 28081,
} as const

export type Environment = keyof typeof IMPORTER_PORTS

export function isEnvironment(mode: string): mode is Environment {
  return mode === 'staging' || mode === 'production'
}

export function importerTarget(mode: string): string {
  if (!isEnvironment(mode)) {
    throw new Error(
      `events-admin needs --mode staging or --mode production, got '${mode}'. ` +
        'Start the forward first: scripts/ej.sh up <env>.',
    )
  }
  return `http://localhost:${IMPORTER_PORTS[mode]}`
}
