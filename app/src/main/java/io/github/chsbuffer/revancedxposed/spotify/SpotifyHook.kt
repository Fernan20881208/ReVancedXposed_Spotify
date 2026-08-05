package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.revancedxposed.BaseHook
import io.github.chsbuffer.revancedxposed.injectHostClassLoaderToSelf
import io.github.chsbuffer.revancedxposed.spotify.misc.privacy.SanitizeSharingLinks
import io.github.chsbuffer.revancedxposed.spotify.misc.widgets.FixThirdPartyLaunchersWidgets

@Suppress("UNCHECKED_CAST")
class SpotifyHook(app: Application, lpparam: LoadPackageParam) : BaseHook(app, lpparam) {
    override val hooks = arrayOf(
        ::Extension,
        ::SanitizeSharingLinks,
        ::FixThirdPartyLaunchersWidgets,
        // Account-state hooks are intentionally disabled in this stability build.
        // They intercept authentication/session responses and can leave Spotify
        // logged out or stuck on the onboarding screen after a server rejection.
        // ::UnlockPremium,
        // ::LogOutPatch,
        // ::NHB
    )

    // ══════════════════════════════════════════════════════
    // EXTENSION LOADER
    // ══════════════════════════════════════════════════════
    fun Extension() {
        injectHostClassLoaderToSelf(this::class.java.classLoader!!, classLoader)
    }

    // ══════════════════════════════════════════════════════
    // NHB → NATIVE HTTP BLOCK
    // Disabled by default. Kept only for source compatibility.
    // ══════════════════════════════════════════════════════
    fun NHB() {
        runCatching {
            val cl = classLoader

            val httpConnectionImpl =
                cl.loadClass("com.spotify.core.http.NativeHttpConnection")

            val httpRequest =
                cl.loadClass("com.spotify.core.http.HttpRequest")

            val urlField = httpRequest.getDeclaredField("url")
            urlField.isAccessible = true

            XposedBridge.hookAllMethods(
                httpConnectionImpl,
                "send",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val req = param.args[0]
                        val url = urlField.get(req) as? String ?: return

                        if (url.contains("ads", true) ||
                            url.contains("tracking", true)
                        ) {
                            XposedBridge.log("NHB BLOCK: $url")
                            param.result = null
                        }
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("NHB error -> ${it.message}")
        }
    }
}
