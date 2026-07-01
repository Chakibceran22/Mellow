import {PermissionsAndroid, Platform} from 'react-native';
import {launchImageLibrary} from 'react-native-image-picker';

/**
 * Let the user pick an image from their device and return it as a small base64
 * `data:` URI (resized to ≤512px, compressed) so it can be stored directly in
 * SQLite as a playlist cover. Returns null if they cancel or it fails.
 *
 * Android 13+ uses the system Photo Picker, which needs no runtime permission;
 * older versions fall back to the storage read grant. iOS surfaces its own
 * permission prompt (needs NSPhotoLibraryUsageDescription in Info.plist).
 */
export async function pickImageAsDataUri(): Promise<string | null> {
  if (Platform.OS === 'android' && Number(Platform.Version) < 33) {
    const perm = PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE;
    const granted =
      (await PermissionsAndroid.check(perm)) ||
      (await PermissionsAndroid.request(perm)) ===
        PermissionsAndroid.RESULTS.GRANTED;
    if (!granted) {
      return null;
    }
  }

  const res = await launchImageLibrary({
    mediaType: 'photo',
    selectionLimit: 1,
    includeBase64: true,
    maxWidth: 512,
    maxHeight: 512,
    quality: 0.8,
  });

  if (res.didCancel) {
    return null;
  }
  if (res.errorCode) {
    console.warn('[imagePicker]', res.errorCode, res.errorMessage);
    return null;
  }

  const asset = res.assets?.[0];
  if (!asset?.base64) {
    return null;
  }
  return `data:${asset.type || 'image/jpeg'};base64,${asset.base64}`;
}
