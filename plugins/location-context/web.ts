import { WebPlugin } from '@capacitor/core';
import type { LocationContextPlugin } from './definitions';

/** Desktop-browser fallback: no geofences, so say so rather than invent a place. */
export class LocationContextWeb extends WebPlugin implements LocationContextPlugin {
  async getCurrentPlace(): Promise<{ place: 'home' | 'gym' | 'other'; since: number | null }> {
    throw this.unavailable('Place detection is only available in the native shell.');
  }
}
