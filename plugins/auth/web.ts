import { WebPlugin } from '@capacitor/core';
import type { ApiCredentials, AuthPlugin } from './definitions';

/**
 * Desktop-browser fallback: there are no native credentials to hand out.
 *
 * Returns empties rather than rejecting so a caller that forgot to feature-detect
 * gets a falsy key (→ fall back to cookie auth) instead of an unhandled rejection.
 */
export class AuthWeb extends WebPlugin implements AuthPlugin {
  async getApiCredentials(): Promise<ApiCredentials> {
    return { apiKey: '', apiUrl: '', deviceId: '' };
  }
}
