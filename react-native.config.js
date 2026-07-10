module.exports = {
  dependency: {
    platforms: {
      // Android only — applying wallpapers programmatically isn't possible on iOS.
      ios: null,
      android: {
        packageImportPath: 'import com.wallpaperengine.WallpaperEnginePackage;',
        packageInstance: 'new WallpaperEnginePackage()',
      },
    },
  },
};
