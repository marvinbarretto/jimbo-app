import type { CapacitorConfig } from '@capacitor/cli';

// Pinning the gym PWA URL via env lets us pin a specific Vercel deployment
// during native rollouts so we don't chase a moving target. Default to the
// rolling latest when CAP_SERVER_URL is unset.
const serverUrl = process.env.CAP_SERVER_URL?.trim() || 'https://gym-kohl-theta.vercel.app';
const serverHost = (() => {
  try {
    return new URL(serverUrl).hostname;
  } catch {
    return 'gym-kohl-theta.vercel.app';
  }
})();

const allowNavigation = Array.from(new Set([
  serverHost,
  '*.vercel.app',
  // jimbo-api itself — gym PWA calls it server-side, but webview can hit it too
  // during dev/diagnostics.
  'jimbo.fourfoldmedia.uk',
]));

const config: CapacitorConfig = {
  appId: 'dev.marvinbarretto.jimbo',
  appName: 'Jimbo',
  webDir: 'public',
  server: {
    url: serverUrl,
    cleartext: serverUrl.startsWith('http://'),
    allowNavigation,
  },
};

export default config;
