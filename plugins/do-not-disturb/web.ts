import { WebPlugin } from '@capacitor/core';
import type { DoNotDisturbPlugin, DoNotDisturbState } from './definitions';

/**
 * Desktop-browser fallback: a browser tab cannot silence anything, so report
 * "no access, not engaged" and make restore a harmless no-op. Enabling rejects
 * rather than pretend the phone went quiet.
 */
export class DoNotDisturbWeb extends WebPlugin implements DoNotDisturbPlugin {
  async getState(): Promise<DoNotDisturbState> {
    return { hasAccess: false, engaged: false };
  }

  async requestAccess(): Promise<void> {
    throw this.unavailable('Do Not Disturb is only available in the native shell.');
  }

  async enable(_options?: { restoreAtMillis?: number }): Promise<void> {
    throw this.unavailable('Do Not Disturb is only available in the native shell.');
  }

  async restore(): Promise<void> {}
}
