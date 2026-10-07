import { registerPlugin } from '@capacitor/core';
import type { LocationContextPlugin } from './definitions';

const LocationContext = registerPlugin<LocationContextPlugin>('LocationContext', {
  web: () => import('./web').then((m) => new m.LocationContextWeb()),
});

export * from './definitions';
export { LocationContext };
