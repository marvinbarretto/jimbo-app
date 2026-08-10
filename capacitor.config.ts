import type { CapacitorConfig } from '@capacitor/cli';

// The WebView loads the dashboard's phone shell (gym PWA demoted to a
// browser surface — migration arc phase 5). CAP_SERVER_URL still overrides
// for pinning a specific deployment during rollouts.
const serverUrl = process.env.CAP_SERVER_URL?.trim() || 'https://jimbo.fourfoldmedia.uk/m';
const serverHost = (() => {
  try {
    return new URL(serverUrl).hostname;
  } catch {
    return 'jimbo.fourfoldmedia.uk';
  }
})();

const allowNavigation = Array.from(new Set([
  serverHost,
  'jimbo.fourfoldmedia.uk',
  // gym PWA — still reachable from the shell for coach chat / voice / history.
  '*.vercel.app',
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
