/**
 * Auth Plugin — Capacitor bridge definitions.
 *
 * Hands the hosted web shell the API credentials the native app already holds
 * in BuildConfig, so it can call jimbo-api without depending on a WebView
 * session cookie surviving.
 *
 * `/api/*` is cookie-OR-X-API-Key and app-gated. Cookies expire and would put
 * a login screen inside the WebView; the key doesn't. It ships in the APK
 * either way, so exposing it to a page we also control doesn't widen the
 * blast radius meaningfully.
 *
 * Consumers must attach the key to same-origin /api requests only, never log
 * it, and fall back to cookie auth when the capability is absent — a desktop
 * browser loading the same build has no bridge.
 */

export const AUTH_VERSION = 1;

export interface ApiCredentials {
  /** X-API-Key value for jimbo-api. Empty string if the APK was built without one. */
  apiKey: string;
  /** Base URL the native side syncs to. Informational — the shell is same-origin with the API. */
  apiUrl: string;
  /** Which phone this is, for attributing writes. */
  deviceId: string;
}

export interface AuthPlugin {
  getApiCredentials(): Promise<ApiCredentials>;
}
