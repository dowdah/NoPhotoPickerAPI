package com.yureitzk.nophotopickerapi

import android.content.Intent
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PickerIntentTransformerTest {
    @Test
    fun routesSingleImageToXiaomiGalleryWithoutPersistableGrant() {
        val original = Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/png")

        val converted = PickerIntentTransformer.toRoutedIntent(original, galleryAvailable = true)

        assertEquals(Intent.ACTION_PICK, converted.action)
        assertEquals(PickerIntentTransformer.XIAOMI_GALLERY_COMPONENT, converted.component)
        assertEquals("image/png", converted.type)
        assertFalse(converted.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertTrue(converted.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(converted.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }

    @Test
    fun preservesMixedImageVideoMimeTypesAndMultipleSelectionForGallery() {
        val mimeTypes = arrayOf("image/jpeg", "video/mp4")
        val original = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        val converted = PickerIntentTransformer.toRoutedIntent(original, galleryAvailable = true)

        assertEquals(Intent.ACTION_PICK, converted.action)
        assertEquals(PickerIntentTransformer.XIAOMI_GALLERY_COMPONENT, converted.component)
        assertEquals("*/*", converted.type)
        assertArrayEquals(mimeTypes, converted.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(converted.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun enablesMultipleSelectionForPhotoPickerMaximum() {
        val original = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 2)
        }

        val converted = PickerIntentTransformer.toRoutedIntent(original, galleryAvailable = true)

        assertTrue(converted.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun fallsBackToOpenDocumentWhenGalleryIsUnavailable() {
        val original = Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/mp4")

        val converted = PickerIntentTransformer.toRoutedIntent(original, galleryAvailable = false)

        assertEquals(PickerIntentTransformer.Route.OPEN_DOCUMENT, PickerIntentTransformer.routeFor(false))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, converted.action)
        assertTrue(converted.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("video/mp4", converted.type)
        assertEquals(null, converted.component)
        assertTrue(converted.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(converted.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }

    @Test
    fun recognizesExternalPickerButNeverReprocessesConvertedIntent() {
        val original = Intent(MediaStore.ACTION_PICK_IMAGES)
        val converted = PickerIntentTransformer.toRoutedIntent(original, galleryAvailable = true)

        assertTrue(PickerIntentTransformer.isRoutableVisualIntent(original))
        assertFalse(PickerIntentTransformer.isRoutableVisualIntent(converted))
        assertTrue(converted.getBooleanExtra(PickerIntentTransformer.HANDLED_EXTRA, false))
    }

    @Test
    fun routesChromeStyleImageGetContentToXiaomiGallery() {
        val original = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")

        val converted = PickerIntentTransformer.toRoutedIntent(original, galleryAvailable = true)

        assertTrue(PickerIntentTransformer.isRoutableVisualIntent(original))
        assertEquals(Intent.ACTION_PICK, converted.action)
        assertEquals(PickerIntentTransformer.XIAOMI_GALLERY_COMPONENT, converted.component)
        assertEquals("image/*", converted.type)
        assertTrue(converted.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun routesMixedVisualGetContentAndPreservesMultipleSelection() {
        val mimeTypes = arrayOf("image/jpeg", "video/mp4")
        val original = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        val converted = PickerIntentTransformer.toRoutedIntent(original, galleryAvailable = true)

        assertTrue(PickerIntentTransformer.isRoutableVisualIntent(original))
        assertEquals("*/*", converted.type)
        assertArrayEquals(mimeTypes, converted.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(converted.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun leavesGenericAndNonVisualGetContentUntouched() {
        val requests = listOf(
            Intent(Intent.ACTION_GET_CONTENT).setType("*/*"),
            Intent(Intent.ACTION_GET_CONTENT).setType("application/pdf"),
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "application/pdf"))
            }
        )

        requests.forEach { request ->
            assertFalse(PickerIntentTransformer.isRoutableVisualIntent(request))
        }
    }

    @Test
    fun routesToKnownXiaomiGalleryWhenPackageVisibilityHidesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue(PickerIntentTransformer.isXiaomiGalleryAvailable(context))
    }
}
