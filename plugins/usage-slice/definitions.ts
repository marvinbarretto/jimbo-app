/**
 * UsageSlice Plugin — Capacitor bridge definitions.
 *
 * Which apps were in the foreground during a window, read straight from
 * UsageStatsManager on the device. The telemetry collector only emits hourly
 * screen sessions and a per-day top-apps list, neither of which can answer
 * "what happened during this 25-minute block" — this can.
 *
 * Facts only: counts and durations. Judging them is the consumer's business.
 * The app's own shell, the launcher and System UI are left out.
 */

export const USAGE_SLICE_VERSION = 1;

export interface UsageSliceApp {
  pkg: string;
  label: string;
  /** Times the app came to the foreground from a different app. */
  launches: number;
  foregroundSeconds: number;
}

export interface UsageSlice {
  /** False when the Usage Access grant is missing, so `apps` being empty means "unknown", not "none". */
  granted: boolean;
  /** Most foreground time first. */
  apps: UsageSliceApp[];
}

export interface UsageSlicePlugin {
  getWindowUsage(options: { fromMillis: number; toMillis: number }): Promise<UsageSlice>;
}
