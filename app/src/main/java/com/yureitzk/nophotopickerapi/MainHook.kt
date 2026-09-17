package com.yureitzk.nophotopickerapi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "NoPhotoPicker"
    }

    fun XC_LoadPackage.LoadPackageParam.isSystemFramework(): Boolean {
        return packageName == "android" || appInfo == null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            lpparam.isSystemFramework() -> {
                hookSystemServices(lpparam)
            }
            lpparam.packageName != null -> {
                hookInstrumentation(lpparam)
                hookActivity(lpparam)
                hookActivityResult(lpparam)
            }
        }
    }

    private fun hookSystemServices(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val serviceClasses = listOf(
            "com.android.server.wm.ActivityTaskManagerService",
            "com.android.server.am.ActivityManagerService",
            "com.android.server.am.ActivityStarter"
        )

        for (className in serviceClasses) {
            val serviceClass = XposedHelpers.findClassIfExists(className, classLoader)
            if (serviceClass != null) {
                hookSystemActivityEntryPoints(serviceClass, className)

                Log.d(TAG, "Hooked $className")
                return
            }
        }
    }

    private fun hookSystemActivityEntryPoints(serviceClass: Class<*>, className: String) {
        for (methodName in AndroidVersionPolicy.systemActivityMethodNames()) {
            XposedBridge.hookAllMethods(
                serviceClass,
                methodName,
                createIntentInterceptor("System:$className.$methodName")
            )
        }
    }

    private fun createIntentInterceptor(source: String): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                for (i in args.indices) {
                    if (args[i] is Intent) {
                        val intent = args[i] as Intent
                        if (isRoutableIntent(intent)) {
                            val routingMode = AndroidVersionPolicy.routingModeForSystem()
                            val galleryAvailable = routingMode == PickerIntentTransformer.RoutingMode.ANDROID_16_HYPEROS_3 &&
                                PickerIntentTransformer.isXiaomiGalleryAvailable(findContext(param))
                            val route = PickerIntentTransformer.routeFor(galleryAvailable, routingMode)
                            logIntentDetails(intent, source, route)
                            val newIntent = PickerIntentTransformer.toRoutedIntent(
                                intent,
                                galleryAvailable,
                                routingMode
                            )
                            args[i] = newIntent

                            if (i + 1 < args.size && (args[i + 1] == null || args[i + 1] is String)) {
                                val newType = newIntent.type ?: "*/*"
                                args[i + 1] = newType
                                Log.d(TAG, "Updated resolvedType to $newType")
                            }
                            return
                        }
                    }
                }
            }
        }
    }

    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.hookAllMethods(
                android.app.Instrumentation::class.java,
                "execStartActivity",
                createIntentInterceptor("Instrumentation.execStartActivity")
            )
            Log.d(TAG, "Hooked Instrumentation for ${lpparam.packageName}")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook Instrumentation: ${t.message}")
        }
    }

    private fun hookActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val activityMethods = listOf("startActivity", "startActivityForResult")

            for (methodName in activityMethods) {
                XposedBridge.hookAllMethods(
                    Activity::class.java,
                    methodName,
                    createIntentInterceptor("App.Activity.$methodName")
                )
            }
            Log.d(TAG, "Successfully hooked Activity methods for ${lpparam.packageName}")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook Activity: ${t.message}")
        }
    }

    private fun hookActivityResult(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val requestCode = param.args[0] as Int
                        val resultCode = param.args[1] as Int
                        val data = param.args[2] as? Intent

                        // Skip if canceled or no data
                        if (resultCode != Activity.RESULT_OK || data == null) return

                        val hasContent = when {
                            data.data != null -> true
                            data.clipData?.let { clipData ->
                                (0 until clipData.itemCount).any { clipData.getItemAt(it).uri != null }
                            } ?: false -> true
                            data.hasExtra(Intent.EXTRA_STREAM) -> true
                            data.hasExtra(Intent.EXTRA_CONTENT_ANNOTATIONS) -> true
                            else -> false
                        }

                        if (!hasContent) {
                            Log.d(TAG, "Empty result detected for request $requestCode")
                            param.args[1] = Activity.RESULT_CANCELED
                            param.args[2] = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook onActivityResult: ${t.message}")
        }
    }

    private fun isRoutableIntent(intent: Intent): Boolean {
        return PickerIntentTransformer.isRoutableVisualIntent(intent)
    }

    private fun findContext(param: XC_MethodHook.MethodHookParam): Context? {
        (param.thisObject as? Context)?.let { return it }
        param.args?.filterIsInstance<Context>()?.firstOrNull()?.let { return it }

        getContextField(param.thisObject)?.let { return it }
        val service = getObjectFieldOrNull(param.thisObject, "mService")
        return getContextField(service)
    }

    private fun getContextField(instance: Any?): Context? {
        return getObjectFieldOrNull(instance, "mContext") as? Context
    }

    private fun getObjectFieldOrNull(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        return try {
            XposedHelpers.getObjectField(instance, fieldName)
        } catch (_: Throwable) {
            null
        }
    }

    private fun logIntentDetails(
        intent: Intent,
        source: String,
        route: PickerIntentTransformer.Route
    ) {
        Log.d(TAG, "[$source] Photo picker detected")
        Log.d(TAG, "  ${PickerIntentTransformer.describeForLog(intent)}")
        Log.d(TAG, "  route=$route")
    }
}
