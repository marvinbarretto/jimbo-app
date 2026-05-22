/**
 * Typed access to `window.__JIMBO_BRIDGE__`, the per-page-load payload
 * injected by the cap shell's BridgeRegistry.
 *
 * Use this from the gym PWA to feature-detect native capabilities and
 * degrade gracefully when running in a desktop browser:
 *
 *   if (bridge.has('telemetry')) {
 *     const status = await Telemetry.getSyncStatus();
 *     ...
 *   }
 *
 * Capabilities are versioned. Increment the version in the cap shell when
 * a plugin adds methods, then gate new-method calls on `bridge.has(name, 2)`.
 */

export interface JimboBridge {
  /** Map of capability name → highest version registered by the cap shell. */
  capabilities: Record<string, number>;
  /** versionName from the cap shell's PackageInfo (matches app/build.gradle versionName). */
  shellVersion: string;
}

declare global {
  interface Window {
    __JIMBO_BRIDGE__?: JimboBridge;
  }
}

const empty: JimboBridge = { capabilities: {}, shellVersion: '0.0.0' };

function payload(): JimboBridge {
  return (typeof window !== 'undefined' && window.__JIMBO_BRIDGE__) || empty;
}

export const bridge = {
  /** True when the cap shell registered `name` at version ≥ minVersion (default 1). */
  has(name: string, minVersion = 1): boolean {
    const v = payload().capabilities[name];
    return typeof v === 'number' && v >= minVersion;
  },

  /** All registered capabilities. Empty object on web. */
  get capabilities(): Record<string, number> {
    return { ...payload().capabilities };
  },

  /** Cap shell versionName, or '0.0.0' on web. */
  get shellVersion(): string {
    return payload().shellVersion;
  },

  /** True when the page is running inside the cap shell (vs. a desktop browser). */
  get isNative(): boolean {
    return typeof window !== 'undefined' && !!window.__JIMBO_BRIDGE__;
  },
};
