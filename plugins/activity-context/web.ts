import { WebPlugin } from '@capacitor/core';
import type { ActivityContext, ActivityContextPlugin } from './definitions';

export class ActivityContextWeb extends WebPlugin implements ActivityContextPlugin {
  async getCurrentActivity(): Promise<ActivityContext> {
    return { state: 'unknown', since: null };
  }
}
