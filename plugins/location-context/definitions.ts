/**
 * LocationContext Plugin — Capacitor bridge definitions.
 *
 * Named place from geofences, never coordinates. Capability `locationContext` v1.
 * The place set is fixed in the native shell (home, gym); anywhere else is `other`.
 */

export const LOCATION_CONTEXT_VERSION = 1;

export type NamedPlace = 'home' | 'gym' | 'other';

export interface LocationContextPlugin {
  getCurrentPlace(): Promise<{
    place: NamedPlace;
    /** Epoch millis of the last transition, or null if none seen yet. */
    since: number | null;
  }>;
}
