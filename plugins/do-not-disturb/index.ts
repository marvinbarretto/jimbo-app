import { registerPlugin } from '@capacitor/core';
import type { DoNotDisturbPlugin } from './definitions';

const DoNotDisturb = registerPlugin<DoNotDisturbPlugin>('DoNotDisturb', {
  web: () => import('./web').then((m) => new m.DoNotDisturbWeb()),
});

export * from './definitions';
export { DoNotDisturb };
