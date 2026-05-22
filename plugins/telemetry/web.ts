import { WebPlugin } from '@capacitor/core';
import type { SyncStatus, TelemetryPlugin } from './definitions';

/**
 * No-op fallback for desktop browsers. Returns "never synced, queue empty" so
 * callers can render the same UI without branching on platform.
 */
export class TelemetryWeb extends WebPlugin implements TelemetryPlugin {
  async getSyncStatus(): Promise<SyncStatus> {
    return {
      lastSyncAt: null,
      pendingCount: 0,
      deadLetterCount: 0,
    };
  }
}
