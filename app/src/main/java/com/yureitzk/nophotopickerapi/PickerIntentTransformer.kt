package com.yureitzk.nophotopickerapi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore

/** Routes external visual-media selection requests to Xiaomi Gallery, with a safe file-picker fallback. */
internal object PickerIntentTransformer {
    const val HANDLED_EXTRA = "x_handled_by_nophoto"
    val XIAOMI_GALLERY_COMPONENT = ComponentName(
        "com.miui.gallery",
        "com.miui.gallery.picker.PickGalleryActivity"
    )
    private const val ANDROIDX_PICK_VISUAL_MEDIA_ACTION =
        "androidx.activity.result.contract.action.PickVisualMedia"
    private const val PLATFORM_PICK_IMAGES_ACTION = "android.provider.action.PICK_IMAGES"

    enum class Route {
        XIAOMI_GALLERY,
        OPEN_DOCUMENT,
        LEGACY_GET_CONTENT
    }

    enum class RoutingMode {
        LEGACY,
        ANDROID_16_HYPEROS_3
    }

    fun isRoutableVisualIntent(intent: Intent): Boolean {
        return isRoutableVisualIntent(
            intent = intent,
            routingMode = AndroidVersionPolicy.routingModeForSystem(),
            photoPickerAvailable = supportsPhotoPickerExtensions()
        )
    }

    internal fun isRoutableVisualIntent(
        intent: Intent,
        routingMode: RoutingMode,
        photoPickerAvailable: Boolean
    ): Boolean {
        if (intent.hasExtra(HANDLED_EXTRA)) return false

        if (photoPickerAvailable && isPhotoPickerIntent(intent)) return true
        return routingMode == RoutingMode.ANDROID_16_HYPEROS_3 && isVisualGetContentIntent(intent)
    }

    private fun isPhotoPickerIntent(intent: Intent): Boolean {
        return intent.action == PLATFORM_PICK_IMAGES_ACTION ||
            intent.action == ANDROIDX_PICK_VISUAL_MEDIA_ACTION
    }

    /**
     * Web file inputs, including Chrome's image upload control, use GET_CONTENT instead of
     * the platform Photo Picker action. Route only unambiguously visual requests: a generic
     * wildcard file request must keep its original chooser.
     */
    private fun isVisualGetContentIntent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_GET_CONTENT) return false

        val extraMimeTypes = getMimeTypesExtra(intent)
        val requestedMimeTypes = extraMimeTypes ?: listOfNotNull(intent.type)
        if (requestedMimeTypes.isEmpty()) return false

        val primaryMimeType = intent.type
        val primaryTypeIsCompatible = primaryMimeType == null ||
            primaryMimeType == "*/*" ||
            isVisualMimeType(primaryMimeType)
        return primaryTypeIsCompatible &&
            requestedMimeTypes.all(::isVisualMimeType)
    }

    private fun isVisualMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/", ignoreCase = true) ||
            mimeType.startsWith("video/", ignoreCase = true)
    }

    fun toRoutedIntent(original: Intent, galleryAvailable: Boolean): Intent {
        return toRoutedIntent(original, galleryAvailable, AndroidVersionPolicy.routingModeForSystem())
    }

    internal fun toRoutedIntent(
        original: Intent,
        galleryAvailable: Boolean,
        routingMode: RoutingMode
    ): Intent {
        val request = PickerRequest.from(original)
        return when {
            routingMode == RoutingMode.LEGACY -> toLegacyGetContentIntent(request)
            galleryAvailable -> toGalleryIntent(request)
            else -> toDocumentIntent(request)
        }
    }

    fun routeFor(galleryAvailable: Boolean): Route {
        return routeFor(galleryAvailable, AndroidVersionPolicy.routingModeForSystem())
    }

    internal fun routeFor(galleryAvailable: Boolean, routingMode: RoutingMode): Route {
        return when {
            routingMode == RoutingMode.LEGACY -> Route.LEGACY_GET_CONTENT
            galleryAvailable -> Route.XIAOMI_GALLERY
            else -> Route.OPEN_DOCUMENT
        }
    }

    fun isXiaomiGalleryAvailable(context: Context?): Boolean {
        // Android 16 system-server entry points do not always expose their Context to an
        // Xposed method hook. On Xiaomi-family ROMs the explicit Gallery component is the
        // selected route; lack of a queryable Context must not accidentally select DocumentsUI.
        if (context == null) return isXiaomiFamilyDevice()

        return try {
            val packageManager = context.packageManager
            val activityInfo = packageManager.getActivityInfo(XIAOMI_GALLERY_COMPONENT, 0)
            activityInfo.enabled && activityInfo.applicationInfo.enabled && activityInfo.exported
        } catch (_: PackageManager.NameNotFoundException) {
            // Apps targeting Android 11+ cannot necessarily query Xiaomi Gallery unless their
            // own manifest declares it in <queries>. The hook runs inside the caller's process,
            // and Android 16 system-server hooks can have the same constrained package view.
            // On Xiaomi-family devices, allow the known explicit component in either case.
            isXiaomiFamilyDevice()
        }
    }

    private fun toGalleryIntent(request: PickerRequest): Intent {
        return Intent(Intent.ACTION_PICK).apply {
            component = XIAOMI_GALLERY_COMPONENT
            type = request.type
            addMimeTypes(this, request)
            addMultipleSelection(this, request)
            putExtra(HANDLED_EXTRA, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun toLegacyGetContentIntent(request: PickerRequest): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.type
            addMimeTypes(this, request)
            addMultipleSelection(this, request)
            putExtra(HANDLED_EXTRA, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun toDocumentIntent(request: PickerRequest): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.type
            addMimeTypes(this, request)
            addMultipleSelection(this, request)
            putExtra(HANDLED_EXTRA, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    private fun addMimeTypes(intent: Intent, request: PickerRequest) {
        if (request.mimeTypes.size > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, request.mimeTypes)
        }
    }

    private fun addMultipleSelection(intent: Intent, request: PickerRequest) {
        if (request.allowMultiple) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    fun describeForLog(intent: Intent): String {
        val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        val mimeDescription = mimeTypes?.joinToString(",") ?: intent.type ?: "image/*"
        val multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) ||
            getMaxItems(intent) > 1
        return "action=${intent.action}, mime=$mimeDescription, multiple=$multiple"
    }

    private fun getMaxItems(intent: Intent): Int {
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2
        ) {
            intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1)
        } else {
            -1
        }
    }

    private fun supportsPhotoPickerExtensions(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2
    }

    private fun isXiaomiFamilyDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        return manufacturer.equals("Xiaomi", ignoreCase = true) ||
            brand.equals("Xiaomi", ignoreCase = true) ||
            brand.equals("Redmi", ignoreCase = true) ||
            brand.equals("POCO", ignoreCase = true)
    }

    private fun getMimeTypesExtra(intent: Intent): List<String>? {
        val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
            ?: intent.getStringArrayExtra("android.provider.extra.MIME_TYPES")
            ?: intent.getStringArrayExtra(
                "androidx.activity.result.contract.extra.PickVisualMedia.MimeType"
            )
            ?: return null
        return mimeTypes.filter { it.isNotBlank() }
    }

    private data class PickerRequest(
        val mimeTypes: Array<String>,
        val type: String,
        val allowMultiple: Boolean
    ) {
        companion object {
            fun from(intent: Intent): PickerRequest {
                val mimeTypes = getMimeTypesExtra(intent)
                    ?: listOf(intent.type ?: "image/*")
                val effectiveMimeTypes = mimeTypes.filter { it.isNotBlank() }
                    .ifEmpty { listOf(intent.type ?: "image/*") }
                    .toTypedArray()

                return PickerRequest(
                    mimeTypes = effectiveMimeTypes,
                    type = if (effectiveMimeTypes.size == 1) effectiveMimeTypes[0] else "*/*",
                    allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) ||
                        getMaxItems(intent) > 1
                )
            }
        }
    }
}
