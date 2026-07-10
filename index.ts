/* eslint-disable @typescript-eslint/no-require-imports -- lazy import on purpose, see WallpaperEnginePreviewView below */
import type { ComponentType } from 'react';
import { Platform, View, type StyleProp, type ViewStyle } from 'react-native';
import NativeWallpaperEngine, { wallpaperEngineEmitter } from './src/NativeWallpaperEngineModule';
import type {
  AutoChangerStateChangeEvent,
  AutoChangerTickEvent,
  DayNightSlot,
  NativeAutoChangerState,
  NativeLiveWallpaperConfig,
  SetLiveWallpaperResult,
  WallpaperChangedEvent,
  WallpaperDownloadProgressEvent,
  WallpaperEngineEventsMap,
} from './src/NativeWallpaperEngineModule';

export type ApplyTarget = 'home' | 'lock' | 'both';
export type LiveWallpaperConfig = NativeLiveWallpaperConfig;
export type { DayNightSlot, SetLiveWallpaperResult };

export interface AutoChangerConfig {
  imageUrls: string[];
  intervalMinutes: number;
}

/** Real autochanger state, as last applied by the native `AutoChangerWorker` (WorkManager) and
 * read from `SharedPreferences` — not a client-computed approximation. */
export type AutoChangerState = NativeAutoChangerState;
export type { AutoChangerTickEvent, AutoChangerStateChangeEvent, WallpaperDownloadProgressEvent, WallpaperChangedEvent, WallpaperEngineEventsMap };

const NOOP_AUTOCHANGER_STATE: AutoChangerState = {
  isRunning: false,
  currentIndex: 0,
  intervalMinutes: 0,
  imageUrls: [],
};

const UNSUPPORTED_ERROR = new Error('react-native-wallpaper-engine: not supported on this platform');

/** Only reached after the `Platform.OS !== 'android'` guards below, so the native module is
 * expected to be linked — a missing module here means autolinking didn't pick up the package. */
function nativeModule() {
  return NativeWallpaperEngine!;
}

/**
 * Applies a static wallpaper (also used for the `double`/`matching` API types — call it once
 * per side). `path` accepts a local file path or a direct remote URL; if remote, the module
 * downloads it itself and reports progress via the `onWallpaperDownloadProgress` event.
 * @throws on non-Android platforms.
 */
export async function setStaticWallpaper(path: string, target: ApplyTarget): Promise<void> {
  if (Platform.OS !== 'android') throw UNSUPPORTED_ERROR;
  return nativeModule().setStaticWallpaper(path, target);
}

/**
 * Applies a live wallpaper (`video`/`daynight`/`parallax`/`ripple`). Always opens the system
 * picker and returns `'preview_opened'` — only that screen can offer the home/lock/both choice
 * for a live wallpaper. If the module's service is already active, the config is hot-swapped
 * once the user confirms; re-check `isLiveWallpaperActive()` (or subscribe to
 * `onWallpaperChanged`) after returning to foreground, since the user can cancel the
 * system preview. Every path field accepts local or remote, same rule as `setStaticWallpaper`.
 * @throws on non-Android platforms.
 */
export async function setLiveWallpaper(config: LiveWallpaperConfig): Promise<SetLiveWallpaperResult> {
  if (Platform.OS !== 'android') throw UNSUPPORTED_ERROR;
  return nativeModule().setLiveWallpaper(config);
}

/** Whether this module's `WallpaperService` is the system's active wallpaper right now. Safe
 * to call on any platform (`false` outside Android). */
export function isLiveWallpaperActive(): boolean {
  if (Platform.OS !== 'android') return false;
  return nativeModule().isLiveWallpaperActive();
}

/**
 * Starts periodic rotation of static wallpapers via a native `WorkManager` chain. Not an API
 * wallpaper type — a client-side feature; `imageUrls` are downloaded by the module itself.
 * @throws on non-Android platforms.
 */
export async function startAutoChanger(config: AutoChangerConfig): Promise<void> {
  if (Platform.OS !== 'android') throw UNSUPPORTED_ERROR;
  return nativeModule().startAutoChanger(config.imageUrls, config.intervalMinutes);
}

/** Stops the autochanger. @throws on non-Android platforms. */
export async function stopAutoChanger(): Promise<void> {
  if (Platform.OS !== 'android') throw UNSUPPORTED_ERROR;
  return nativeModule().stopAutoChanger();
}

/** Reads the real autochanger state from native storage. Use it to resync UI on mount and as a
 * safety net for events missed while the app was backgrounded. Safe on any platform (returns an
 * "off" state outside Android). */
export async function getAutoChangerState(): Promise<AutoChangerState> {
  if (Platform.OS !== 'android') return NOOP_AUTOCHANGER_STATE;
  return nativeModule().getAutoChangerState();
}

/**
 * Subscribes to a wallpaper engine event and returns an unsubscribe function. Event names and
 * listener payloads are fully typed via `WallpaperEngineEventsMap`; no-op outside Android.
 * Autochanger events are best-effort (dropped if the app process isn't alive when the worker
 * runs) — reconcile with `getAutoChangerState()` on foreground.
 */
export function subscribe<TEventName extends keyof WallpaperEngineEventsMap>(
  eventName: TEventName,
  listener: WallpaperEngineEventsMap[TEventName]
): () => void {
  if (Platform.OS !== 'android' || !wallpaperEngineEmitter) return () => {};
  const subscription = wallpaperEngineEmitter.addListener(eventName, listener as (event: unknown) => void);
  return () => subscription.remove();
}

/** Day/night cutoff mirrored from the native `DayNightSchedule` for the non-Android fallback —
 * keep in sync with the Kotlin constant if this ever changes. */
const JS_DAY_START_HOUR = 6;
const JS_DAY_END_HOUR_EXCLUSIVE = 18;

/** Resolves which `daynight` slot (day/night) corresponds to the current local time — the same
 * rule the applied `daynight` wallpaper uses, so a preview never diverges. Synchronous by
 * design so callers can use it on every render. */
export function resolveDayNightSlot(): DayNightSlot {
  if (Platform.OS !== 'android') {
    const hour = new Date().getHours();
    return hour >= JS_DAY_START_HOUR && hour < JS_DAY_END_HOUR_EXCLUSIVE ? 'day' : 'night';
  }
  return nativeModule().resolveDayNightSlot();
}

export interface WallpaperEnginePreviewViewProps {
  /** Same discriminated union as `setLiveWallpaper`. Only `parallax`/`ripple` render anything
   * (they need sensors/touch); other types draw nothing. */
  config: LiveWallpaperConfig;
  style?: StyleProp<ViewStyle>;
}

/**
 * Interactive in-app preview: `parallax` responds to device sensors, `ripple` to touch, reusing
 * the exact same native renderers the applied wallpaper uses. Android only — renders an empty
 * `View` elsewhere so callers don't need extra platform checks.
 */
export const WallpaperEnginePreviewView: ComponentType<WallpaperEnginePreviewViewProps> =
  Platform.OS === 'android'
    ? require('./src/WallpaperEnginePreviewView').default
    : (View as unknown as ComponentType<WallpaperEnginePreviewViewProps>);
