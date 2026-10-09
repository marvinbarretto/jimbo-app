import { registerPlugin } from '@capacitor/core';
import type { UsageSlicePlugin } from './definitions';

const UsageSlice = registerPlugin<UsageSlicePlugin>('UsageSlice', {
  web: () => import('./web').then((m) => new m.UsageSliceWeb()),
});

export * from './definitions';
export { UsageSlice };
