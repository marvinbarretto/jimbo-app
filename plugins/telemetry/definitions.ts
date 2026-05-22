/**
 * Telemetry Plugin — Capacitor bridge definitions.
 *
 * Exposes a read-only view of the cap shell's local telemetry queue:
 * - when the last successful sync ran
 * - how many events are still waiting to be POSTed
 * - how many events were permanently rejected (dead-letter)
 *
 * Consumed by the gym PWA to show "last sync 3 min ago", warn when the
 * queue is backing up, or surface dead-letter counts to the user.
 *
 * Read-only on purpose: triggerSync() and other actions are deferred to a
 * later plugin version. Increment TELEMETRY_VERSION when adding methods.
 */

export const TELEMETRY_VERSION = 1;

export interface SyncStatus {
  /** Epoch millis of the most recent successfully-synced event, or null if never synced. */
  lastSyncAt: number | null;
  /** Events queued locally but not yet POSTed to /api/telemetry/events. */
  pendingCount: number;
  /** Events that failed permanently or exhausted retries. */
  deadLetterCount: number;
}

export interface TelemetryPlugin {
  getSyncStatus(): Promise<SyncStatus>;
}
