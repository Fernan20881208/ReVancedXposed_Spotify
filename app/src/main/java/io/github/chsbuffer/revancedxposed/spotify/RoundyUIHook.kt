package io.github.chsbuffer.revancedxposed.spotify

import android.content.res.Resources
import android.graphics.Outline
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class RoundyUIHook(private val lpparam: XC_LoadPackage.LoadPackageParam) {

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            Resources.getSystem().displayMetrics
        )
    }

    private val radiusLarge = dpToPx(28f)
    private val radiusFull = dpToPx(100f)
    private val TAG = "SpotifyRoundyUIDebug"

    fun hook() {
        val classLoader = lpparam.classLoader

        /*
        // 0. Hook UNIVERSALE con LOG di Debug
        XposedHelpers.findAndHookMethod(
            "android.view.View",
            classLoader,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View

                    // DEBUG: Estraiamo l'ID per capire cosa stiamo toccando
                    val resName = try { view.resources.getResourceEntryName(view.id) } catch (_: Exception) { "null" }
                    if (view is ImageView || resName != "null") {
                        Log.d(TAG, "View rilevata: ID -> $resName | Classe -> ${view.javaClass.simpleName}")
                    }

                    applyRoundingIfTarget(view)
                }
            }
        )
         */

        // 1. Hook UNIVERSALE per lo stondamento basato su ID e Classe
        XposedHelpers.findAndHookMethod(
            "android.view.View",
            classLoader,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    applyRoundingIfTarget(view)
                }
            }
        )

        // 2. Hook specifico per le ImageView (Copertine Playlist)
        XposedHelpers.findAndHookMethod(
            "android.widget.ImageView",
            classLoader,
            "setImageDrawable",
            android.graphics.drawable.Drawable::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val imageView = param.thisObject as ImageView
                    applyRoundingIfTarget(imageView)
                }
            }
        )

        // 3. Hook per i GradientDrawable (Pulsanti)
        XposedHelpers.findAndHookMethod(
            "android.graphics.drawable.GradientDrawable",
            classLoader,
            "setCornerRadius",
            Float::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args[0] as Float
                    param.args[0] = if (original > dpToPx(15f)) radiusFull else radiusLarge
                }
            }
        )

        // 4. Hook per i BottomSheets. Se Spotify cambia la firma, non blocchiamo il resto del modulo.
        runCatching {
            val bottomSheetClass = XposedHelpers.findClass(
                "com.google.android.material.bottomsheet.BottomSheetBehavior",
                classLoader
            )

            val unhooks = XposedBridge.hookAllMethods(
                bottomSheetClass,
                "onLayoutChild",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.args.getOrNull(1) as? View ?: return
                        view.clipToOutline = true
                        view.outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setRoundRect(
                                    0,
                                    0,
                                    view.width,
                                    view.height + radiusLarge.toInt(),
                                    radiusLarge
                                )
                            }
                        }
                    }
                }
            )

            if (unhooks.isEmpty()) {
                XposedBridge.log("RoundyUI: BottomSheetBehavior.onLayoutChild non trovato; hook ignorato")
            }
        }.onFailure {
            XposedBridge.log(
                "RoundyUI: hook BottomSheet ignorato: ${it.javaClass.simpleName}: ${it.message}"
            )
        }

        // 5. Hook di rinforzo per i background dei BottomSheet.
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.google.android.material.shape.MaterialShapeDrawable",
                classLoader,
                "setInterpolation",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedHelpers.callMethod(param.thisObject, "setCornerSize", radiusLarge)
                    }
                }
            )
        }.onFailure {
            XposedBridge.log(
                "RoundyUI: hook MaterialShapeDrawable ignorato: ${it.javaClass.simpleName}: ${it.message}"
            )
        }
    }

    private fun applyRoundingIfTarget(view: View) {
        val resName = try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Exception) {
            ""
        }
        val className = view.javaClass.name.lowercase()

        val isAvatar = className.contains("faceview") || resName.contains("face")
        val isImage = view is ImageView || className.contains("imageview") && !isAvatar

        val isCoverOrHeader = resName.contains("header") ||
            resName.contains("cover") ||
            resName.contains("art") ||
            resName.contains("entity")

        val isSheet = className.contains("bottomsheet") ||
            resName.contains("sheet") ||
            resName.contains("queue")
        val isCard = className.contains("card") || resName.contains("tile")
        val isSearchBar = resName == "browse_search_bar_container" || resName.contains("search")
        val isCat = resName == "seek_frame" || resName.contains("seek")

        val isTextContainerRow =
            (resName.contains("row") || resName.contains("item")) && !isImage && !isSheet

        val shouldRound = when {
            isAvatar -> false
            isImage -> true
            isCoverOrHeader -> true
            isSheet -> true
            isCard -> true
            isSearchBar -> true
            isCat -> true
            else -> false
        }

        if (shouldRound) {
            if (isTextContainerRow) {
                view.clipToOutline = false
                return
            }

            view.clipToOutline = true
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (isSheet) {
                        outline.setRoundRect(
                            0,
                            0,
                            view.width,
                            view.height + radiusLarge.toInt(),
                            radiusLarge
                        )
                    } else {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusLarge)
                    }
                }
            }
        }
    }
}
