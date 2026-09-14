# NoPhotoPickerAPI

[![GitHub license](https://img.shields.io/github/license/dowdah/NoPhotoPickerAPI)](https://github.com/dowdah/NoPhotoPickerAPI/blob/main/LICENSE)

An Xposed/LSPosed module that intercepts external Android
[Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
requests. On Xiaomi-family devices it routes them to Xiaomi Gallery's normal
picker (`com.miui.gallery/.picker.PickGalleryActivity`) instead of HyperOS's
privacy picker. When Xiaomi Gallery cannot be selected on a non-Xiaomi route,
the module falls back to the standard `OPEN_DOCUMENT` file picker.

> [!IMPORTANT]
> Tested on Xiaomi 13 (nuwa), Android 16 / API 36, HyperOS 3.0.309.0 with
> KernelSU Zygisk and LSPosed. Other devices and ROM builds may behave differently.

## Supported OSes

- Android 11-16 (API 30-36)
- Xiaomi HyperOS 3 on Android 16 is supported and verified on the device above.
- Other ROMs retain the file-picker fallback where the target Gallery component is not used.

## What is intercepted

- External `ACTION_PICK_IMAGES` requests, the Jetpack visual-media compatibility action, and
  visual-only `ACTION_GET_CONTENT` requests (including Chrome web image uploads).
- Image, video, mixed MIME, and multi-select requests are preserved when routed.
- Generic `GET_CONTENT` requests without an explicit visual MIME list (including bare `*/*`),
  non-visual files such as PDFs, permission prompts, app-owned gallery UIs, and Android 16
  embedded Photo Picker sessions are deliberately not intercepted.

## Usage

1. Install version 0.5 or later and enable the module in LSPosed.
2. Select **System Framework** (`android`). This is the default and covers apps
   that use the external Photo Picker API.
3. Reboot the device.
4. Keep Xiaomi Gallery enabled. If the module APK is updated, verify the
   LSPosed scope again before rebooting because some setups reset module state
   after an APK replacement.

For a limited, per-app rollout, select that app instead of `android`. The
system-framework scope is required for broad coverage.

## Important notes

- Xiaomi-family routing requires the Xiaomi Gallery picker component to remain
  present and enabled.
- The module changes the picker UI, but still grants the caller read access only
  to media selected by the user.
- This is not a replacement for embedded Photo Picker integrations or an app's
  own media UI.
- Use at your own risk.
