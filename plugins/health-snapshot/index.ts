import { registerPlugin } from '@capacitor/core';
import type { HealthSnapshotPlugin } from './definitions';

const HealthSnapshot = registerPlugin<HealthSnapshotPlugin>('HealthSnapshot', {
  web: () => import('./web').then(m => new m.HealthSnapshotWeb()),
});

export * from './definitions';
export { HealthSnapshot };
