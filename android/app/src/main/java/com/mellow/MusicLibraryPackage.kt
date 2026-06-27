package com.mellow

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * Registers the MusicLibrary TurboModule. Local app modules aren't autolinked,
 * so this package is added by hand in MainApplication.
 */
class MusicLibraryPackage : BaseReactPackage() {

  override fun getModule(
    name: String,
    reactContext: ReactApplicationContext,
  ): NativeModule? =
    if (name == MusicLibraryModule.NAME) MusicLibraryModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      MusicLibraryModule.NAME to ReactModuleInfo(
        MusicLibraryModule.NAME, // name
        MusicLibraryModule.NAME, // className
        false, // canOverrideExistingModule
        false, // needsEagerInit
        false, // isCxxModule
        true,  // isTurboModule
      ),
    )
  }
}
