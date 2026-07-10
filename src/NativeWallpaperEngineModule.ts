import { NativeEventEmitter, NativeModules } from 'react-native';

/** Raw shape crossing the bridge for `LiveWallpaperConfig` (see `index.ts`). */
export type NativeLiveWallpaperConfig =
  | { type: 'video'; videoPath: string }
  | {
    type: 'daynight';
    dayImagePath: string;
    nightImagePath: string;
  }
  // `parallax`: either the API's zip, or loose layers assembled by the caller (no zip) — array
  // order defines z-order (index 0 = background, last = moves most).
  | { type: 'parallax'; zipPath: string; layers?: never }
  | { type: 'parallax'; layers: string[]; zipPath?: never }
  // `ripple`: image or video — video wins if both are provided.
  | { type: 'ripple'; videoPath: string; imagePath?: never }
  | { type: 'ripple'; imagePath: string; videoPath?: never };

export type SetLiveWallpaperResult = 'applied' | 'preview_opened';

export type DayNightSlot = 'day' | 'night';

/** Real autochanger state read from native `SharedPreferences`. */
export interface NativeAutoChangerState {
  isRunning: boolean;
  currentIndex: number;
  intervalMinutes: number;
  imageUrls: string[];
}

export interface AutoChangerTickEvent {
  index: number;
  url: string;
}

export interface AutoChangerStateChangeEvent {
  isRunning: boolean;
}

export interface WallpaperDownloadProgressEvent {
  progress: number;
}

export interface WallpaperChangedEvent {
  isLiveWallpaperActive: boolean;
}

export type WallpaperEngineEventsMap = {
  onAutoChangerTick: (event: AutoChangerTickEvent) => void;
  onAutoChangerStateChange: (event: AutoChangerStateChangeEvent) => void;
  onWallpaperDownloadProgress: (event: WallpaperDownloadProgressEvent) => void;
  onWallpaperChanged: (event: WallpaperChangedEvent) => void;
};

/** Raw native module declaration (Android only). Don't import this directly — use the typed
 * wrapper in `index.ts`, which adds platform guards. */
export interface NativeWallpaperEngineModule {
  setStaticWallpaper(path: string, target: 'home' | 'lock' | 'both'): Promise<void>;
  setLiveWallpaper(config: NativeLiveWallpaperConfig): Promise<SetLiveWallpaperResult>;
  isLiveWallpaperActive(): boolean;
  resolveDayNightSlot(): DayNightSlot;
  startAutoChanger(imageUrls: string[], intervalMinutes: number): Promise<void>;
  stopAutoChanger(): Promise<void>;
  getAutoChangerState(): Promise<NativeAutoChangerState>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

const NativeWallpaperEngine: NativeWallpaperEngineModule | undefined = NativeModules.WallpaperEngine;

export default NativeWallpaperEngine;

/** Shared event emitter for the module — `NativeEventEmitter` requires the native module
 * instance itself (for `addListener`/`removeListeners`), so this is created once and reused by
 * every `subscribe()` call instead of once per listener. */
export const wallpaperEngineEmitter = NativeWallpaperEngine
  ? new NativeEventEmitter(NativeModules.WallpaperEngine)
  : undefined;
