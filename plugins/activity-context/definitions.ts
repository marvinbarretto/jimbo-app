export const ACTIVITY_CONTEXT_VERSION = 1;

export type ActivityState =
  | 'still'
  | 'walking'
  | 'on_foot'
  | 'running'
  | 'on_bicycle'
  | 'in_vehicle'
  | 'unknown';

export interface ActivityContext {
  /** Current activity state. Derived from the last ENTER transition — held across EXIT
   *  until the next ENTER, so never flickers to unknown mid-transition. */
  state: ActivityState;
  /** Epoch millis when this state was last entered. Null if no transition seen yet. */
  since: number | null;
}

export interface ActivityContextPlugin {
  getCurrentActivity(): Promise<ActivityContext>;
}
