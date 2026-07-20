import { WebPlugin } from '@capacitor/core';
import type { HealthSnapshot, HealthSnapshotPlugin } from './definitions';

export class HealthSnapshotWeb extends WebPlugin implements HealthSnapshotPlugin {
  async getTodaySnapshot(): Promise<HealthSnapshot> {
    throw this.unimplemented('HealthSnapshot is only available on Android.');
  }
}
