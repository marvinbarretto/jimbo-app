import { registerPlugin } from '@capacitor/core';
import type { ActivityContextPlugin } from './definitions';

const ActivityContext = registerPlugin<ActivityContextPlugin>('ActivityContext', {
  web: () => import('./web').then(m => new m.ActivityContextWeb()),
});

export * from './definitions';
export { ActivityContext };
