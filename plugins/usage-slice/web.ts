import { WebPlugin } from '@capacitor/core';
import type { UsageSlice, UsageSlicePlugin } from './definitions';

/** Desktop-browser fallback: no usage stats exist, so say "not granted" rather than "nothing used". */
export class UsageSliceWeb extends WebPlugin implements UsageSlicePlugin {
  async getWindowUsage(_options: { fromMillis: number; toMillis: number }): Promise<UsageSlice> {
    return { granted: false, apps: [] };
  }
}
