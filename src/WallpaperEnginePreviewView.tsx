import { requireNativeComponent, type StyleProp, type ViewStyle } from 'react-native';
import type { NativeLiveWallpaperConfig } from './NativeWallpaperEngineModule';

export interface NativeWallpaperEnginePreviewViewProps {
  config: NativeLiveWallpaperConfig;
  style?: StyleProp<ViewStyle>;
}

/** Raw native view (Android only) — use `WallpaperEnginePreviewView` from `index.ts` instead,
 * which adds the platform guard. */
export default requireNativeComponent<NativeWallpaperEnginePreviewViewProps>('WallpaperEnginePreviewView');
