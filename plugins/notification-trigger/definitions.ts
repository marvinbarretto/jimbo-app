/**
 * NotificationTrigger Plugin — Capacitor bridge definitions.
 *
 * Plumbing only: lets the hosted shell ask Android to post a notification at a
 * given time. Consumers (briefing, nudges, gym) decide what to say and when.
 *
 * Channels are created natively so Android's per-channel mute is the user's
 * frequency control. Tapping a notification opens the shell at `/m/<tab>`.
 *
 * Alarms are inexact (setAndAllowWhileIdle) and do not survive a reboot.
 */

export const NOTIFICATION_VERSION = 1;

export type NotificationChannelId = 'briefing' | 'nudges' | 'gym';

export interface ScheduleNotificationOptions {
  /** Caller-chosen id. Scheduling the same id again replaces the earlier one. */
  id: string;
  title: string;
  body: string;
  /** Epoch millis to fire at. A past or current time fires immediately. */
  atMillis: number;
  /** Defaults to 'nudges'. */
  channelId?: NotificationChannelId;
  /** Shell tab opened on tap, i.e. `/m/<tab>` (e.g. 'today', 'log', 'train'). Defaults to 'today'. */
  tab?: string;
}

export interface NotificationTriggerPlugin {
  schedule(options: ScheduleNotificationOptions): Promise<void>;
  cancel(options: { id: string }): Promise<void>;
}
