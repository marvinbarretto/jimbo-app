/**
 * DoNotDisturb Plugin — Capacitor bridge definitions.
 *
 * Lets a focus block silence the phone and give it back. Plumbing only: the
 * shell decides when a block starts and ends.
 *
 * Needs the "Do Not Disturb access" special grant, which cannot be requested
 * in a dialog — `requestAccess()` opens the system settings page for it.
 *
 * `enable()` remembers the user's previous interruption filter and `restore()`
 * puts exactly that back, so a phone that was already on Priority-only stays
 * there. Pass `restoreAtMillis` to `enable()` and native restores at that time
 * on its own, so a block that ends while the WebView is suspended or killed
 * still hands the phone back.
 */

export const DO_NOT_DISTURB_VERSION = 1;

export interface DoNotDisturbState {
  /** The DND access grant is held. */
  hasAccess: boolean;
  /** A block turned DND on and has not yet restored it. */
  engaged: boolean;
}

export interface DoNotDisturbPlugin {
  getState(): Promise<DoNotDisturbState>;
  /** Opens the system settings page where the grant is given. Resolves immediately. */
  requestAccess(): Promise<void>;
  /** Turns on Priority-only DND. Rejects with `access_not_granted` without the grant. */
  enable(options?: { restoreAtMillis?: number }): Promise<void>;
  /** Restores the filter that was set before `enable()`. A no-op when not engaged. */
  restore(): Promise<void>;
}
