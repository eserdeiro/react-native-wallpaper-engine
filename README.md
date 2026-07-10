# react-native-wallpaper-engine

Apply every kind of Android wallpaper — static, paired (double/matching), video, day/night,
parallax, and ripple — plus a client-side autochanger, all from one small native module. Works
in any React Native app, Expo or bare.

## Features

- **Static wallpapers** (`static`/`double`/`matching`) via `WallpaperManager`, with real
  cover-crop against the device's actual screen size.
- **Live wallpapers** (`video`, `daynight`, `parallax`, `ripple`) via a bundled
  `WallpaperService` — no native code to write.
- **Local or remote paths everywhere.** Pass a `file://` path or a direct `http(s)://` URL; the
  module downloads remote assets itself and reports real progress.
- **Interactive in-app preview** (`WallpaperEnginePreviewView`) for `parallax`/`ripple`, reusing
  the exact same renderers the applied wallpaper uses.
- **Day/night helper** (`resolveDayNightSlot`) so an in-app preview never diverges from the
  applied `daynight` wallpaper's current slot.
- **Autochanger**: periodic rotation of static wallpapers via `WorkManager`, with native events
  and a state query to resync your UI.

## Platform support

Android only. Off Android, every export is still safe to call: actions throw an
`UNSUPPORTED_ERROR`, queries return a safe default, and `subscribe`/`WallpaperEnginePreviewView`
are no-ops — so cross-platform code can call everything unconditionally.

## Requirements

React Native 0.71+. Works in both Expo (managed or prebuild) and bare React Native CLI.

## Installation

```
npm install react-native-wallpaper-engine
```

This module has native Android code, so it doesn't work in Expo Go — run a prebuild after
installing:

```
npx expo prebuild
```

(bare RN CLI autolinks it automatically, no extra step). Android permissions
(`SET_WALLPAPER`/`SET_WALLPAPER_HINTS`) are merged into your app automatically.

`INTERNET` is also required for remote assets — not declared here since virtually every RN app
already has it.

The label/description shown in Android's live wallpaper picker ("Live Wallpaper") can be
overridden by declaring `live_wallpaper_label`/`live_wallpaper_description` in your own app's
`strings.xml` — app resources take precedence over the library's defaults.

## Quick start

```ts
import { setStaticWallpaper } from 'react-native-wallpaper-engine';

await setStaticWallpaper('https://example.com/photo.jpg', 'both');
```

## Usage

### Static wallpapers (`static` / `double` / `matching`)

```ts
import { setStaticWallpaper } from 'react-native-wallpaper-engine';

// A single image for both home and lock screen:
await setStaticWallpaper(imagePath, 'both');

// A pair (double/matching) — call once per side:
await setStaticWallpaper(homeImagePath, 'home');
await setStaticWallpaper(lockImagePath, 'lock');
```

`path` accepts a local file or a remote URL — remote ones are downloaded, applied, then deleted.

### Live wallpapers (`video` / `daynight` / `parallax` / `ripple`)

```ts
type LiveWallpaperConfig =
  | { type: 'video'; videoPath: string }
  | { type: 'daynight'; dayImagePath: string; nightImagePath: string }
  | { type: 'parallax'; zipPath: string }   // API-provided zip
  | { type: 'parallax'; layers: string[] }  // loose layers, no zip — array order is z-order
  | { type: 'ripple'; videoPath: string }   // animated ripple over a looping video
  | { type: 'ripple'; imagePath: string };  // animated ripple over a static image
```

```ts
import { setLiveWallpaper, isLiveWallpaperActive, subscribe } from 'react-native-wallpaper-engine';

await setLiveWallpaper({ type: 'video', videoPath: remoteOrLocalVideoPath });
// Always resolves 'preview_opened' — the system picker confirms home/lock/both. Re-check after:
const unsubscribe = subscribe('onWallpaperChanged', ({ isLiveWallpaperActive }) => {
  console.log('active:', isLiveWallpaperActive);
});
```

**Parallax zip** — no manifest needed, layers come from file names:

```
parallax_data.zip
├─ 0.jpg        ← leading digit = z-order (0 = background, moves least)
├─ 1.png
├─ 2.png        ← highest digit = foreground, moves most
└─ 1_mask.jpg   ← *_mask.* files are depth maps — ignored, never drawn
```

Or skip the zip and assemble your own layers (array order = z-order):

```ts
await setLiveWallpaper({ type: 'parallax', layers: [backgroundUrl, midgroundUrl, foregroundUrl] });
```

`ripple` accepts a video or a static image — video wins if both are given.

### Interactive in-app preview

`WallpaperEnginePreviewView` renders a live preview for `parallax` (sensors) and `ripple`
(touch), reusing the same native renderer as the applied wallpaper. Other types render nothing.

```tsx
import { WallpaperEnginePreviewView, type LiveWallpaperConfig } from 'react-native-wallpaper-engine';

const config: LiveWallpaperConfig = { type: 'parallax', zipPath: localOrRemoteZipPath };

<WallpaperEnginePreviewView config={config} style={{ flex: 1 }} />;
```

### Download progress

```ts
import { subscribe } from 'react-native-wallpaper-engine';

subscribe('onWallpaperDownloadProgress', ({ progress }) => {
  console.log(`${Math.round(progress * 100)}%`);
});
```

### Day/night helper

```ts
import { resolveDayNightSlot } from 'react-native-wallpaper-engine';

resolveDayNightSlot(); // 'day' | 'night', 06:00-18:00 local time = day
```

Use it to pick which bitmap to show in your own preview UI so it matches the applied wallpaper.

### Autochanger

Not an API wallpaper type — rotates static wallpapers on a timer via `WorkManager`.

```ts
import { startAutoChanger, stopAutoChanger, getAutoChangerState, subscribe } from 'react-native-wallpaper-engine';

await startAutoChanger({ imageUrls: [url1, url2, url3], intervalMinutes: 60 });

const state = await getAutoChangerState(); // resync UI with the real native state
subscribe('onAutoChangerTick', ({ index, url }) => { /* applied url at index */ });

await stopAutoChanger();
```

## API reference

### Functions

| Function | Signature |
|---|---|
| `setStaticWallpaper` | `(path: string, target: 'home' \| 'lock' \| 'both') => Promise<void>` |
| `setLiveWallpaper` | `(config: LiveWallpaperConfig) => Promise<'applied' \| 'preview_opened'>`¹ |
| `isLiveWallpaperActive` | `() => boolean` |
| `resolveDayNightSlot` | `() => 'day' \| 'night'` |
| `startAutoChanger` | `(config: AutoChangerConfig) => Promise<void>` |
| `stopAutoChanger` | `() => Promise<void>` |
| `getAutoChangerState` | `() => Promise<AutoChangerState>` |
| `subscribe` | `(event: keyof WallpaperEngineEventsMap, listener) => () => void` |

¹ `'applied'` is reserved for a possible future direct-apply path — today this always resolves
`'preview_opened'`.

### Events

| Event | Payload | Notes |
|---|---|---|
| `onAutoChangerTick` | `{ index: number; url: string }` | Best-effort — app process must be alive |
| `onAutoChangerStateChange` | `{ isRunning: boolean }` | Best-effort |
| `onWallpaperDownloadProgress` | `{ progress: number }` (0..1) | Always delivered |
| `onWallpaperChanged` | `{ isLiveWallpaperActive: boolean }` | Fires on any confirmed wallpaper change |

### Types

```ts
type ApplyTarget = 'home' | 'lock' | 'both';
type SetLiveWallpaperResult = 'applied' | 'preview_opened';
type DayNightSlot = 'day' | 'night';

interface AutoChangerConfig {
  imageUrls: string[];
  intervalMinutes: number;
}

interface AutoChangerState {
  isRunning: boolean;
  currentIndex: number;
  intervalMinutes: number;
  imageUrls: string[];
}

interface WallpaperEnginePreviewViewProps {
  config: LiveWallpaperConfig;
  style?: StyleProp<ViewStyle>;
}
```

## Caveats

- **One live wallpaper at a time** — an Android constraint, not this module's.
- **Autochanger events are best-effort** — dropped if the app process isn't alive; call
  `getAutoChangerState()` on foreground to resync.
- **No asset cache cleanup** — persistent downloads (`video`/`daynight`/`ripple`) aren't pruned
  automatically.

## License

MIT
