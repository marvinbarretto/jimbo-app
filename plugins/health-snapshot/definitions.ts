export const HEALTH_SNAPSHOT_VERSION = 1;

export interface HealthSnapshot {
  /** Total steps since midnight. */
  steps: number;
  /** Active calories burned (kcal). */
  activeCalories: number;
  /** Average heart rate today (bpm), or null if no HR data. */
  heartRateAvg: number | null;
  /** Min heart rate today (bpm), or null if no HR data. Note: min != resting HR. */
  heartRateMin: number | null;
  /** Max heart rate today (bpm), or null if no HR data. */
  heartRateMax: number | null;
  /** Total sleep duration in ms for sessions within today's window, or null if none.
   *  Note: HC sleep sessions that started before midnight may not be included. */
  sleepDurationMs: number | null;
  /** Number of exercise sessions recorded today. */
  exerciseSessionCount: number;
  /** Epoch millis when this snapshot was read from Health Connect. */
  asOf: number;
}

export interface HealthSnapshotPlugin {
  /** Rejects if Health Connect is not installed or permissions are not granted. */
  getTodaySnapshot(): Promise<HealthSnapshot>;
}
