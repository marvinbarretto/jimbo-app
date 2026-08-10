import { registerPlugin } from '@capacitor/core';
import type { AuthPlugin } from './definitions';

const Auth = registerPlugin<AuthPlugin>('Auth', {
  web: () => import('./web').then((m) => new m.AuthWeb()),
});

export * from './definitions';
export { Auth };
