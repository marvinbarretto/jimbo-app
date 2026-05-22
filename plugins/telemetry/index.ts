import { registerPlugin } from '@capacitor/core';
import type { TelemetryPlugin } from './definitions';

const Telemetry = registerPlugin<TelemetryPlugin>('Telemetry', {
  web: () => import('./web').then((m) => new m.TelemetryWeb()),
});

export * from './definitions';
export { Telemetry };
