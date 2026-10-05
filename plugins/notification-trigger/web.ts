import { WebPlugin } from '@capacitor/core';
import type { NotificationTriggerPlugin, ScheduleNotificationOptions } from './definitions';

/**
 * Desktop-browser fallback: there is no native notification surface, so reject
 * rather than pretend a nudge was scheduled.
 */
export class NotificationTriggerWeb extends WebPlugin implements NotificationTriggerPlugin {
  async schedule(_options: ScheduleNotificationOptions): Promise<void> {
    throw this.unavailable('Notifications are only available in the native shell.');
  }

  async cancel(_options: { id: string }): Promise<void> {
    throw this.unavailable('Notifications are only available in the native shell.');
  }
}
