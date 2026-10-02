import { registerPlugin } from '@capacitor/core';
import type { NotificationTriggerPlugin } from './definitions';

const NotificationTrigger = registerPlugin<NotificationTriggerPlugin>('NotificationTrigger', {
  web: () => import('./web').then((m) => new m.NotificationTriggerWeb()),
});

export * from './definitions';
export { NotificationTrigger };

/**
 * Test flow: fires one notification now on the nudges channel; tapping it opens `/m/<tab>`.
 * Gate on `bridge.has('notification', 1)` — it rejects off-device.
 */
export function sendTestNotification(tab = 'today'): Promise<void> {
  return NotificationTrigger.schedule({
    id: 'test',
    title: 'Jimbo test notification',
    body: `Tap to open /m/${tab}`,
    atMillis: Date.now(),
    channelId: 'nudges',
    tab,
  });
}
