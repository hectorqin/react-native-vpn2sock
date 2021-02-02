package com.htmake.vpn2sock

import android.content.ActivityNotFoundException
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import org.json.JSONObject
import org.outline.OutlinePlugin
import org.outline.shadowsocks.ShadowsocksConnectivity
import java.util.*


class Vpn2sockModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private var vpnPlugin: OutlinePlugin;

    init {
      this.vpnPlugin = OutlinePlugin()
      this.vpnPlugin.setReactContext(this.reactContext)
    }

    override fun getName(): String {
        return "Vpn2sock"
    }

    // Example method
    // See https://reactnative.dev/docs/native-modules-android
    @ReactMethod
    fun multiply(a: Int, b: Int, promise: Promise) {

      promise.resolve(a * b)

    }

    @ReactMethod
    fun startTunnel(tunnelId: String, config: JSONObject, promise: Promise) {
      try {
        if (!this.vpnPlugin.prepareVpnService()) {
          // TODO 等待VPN添加
        }
      } catch (e: ActivityNotFoundException) {
        promise.reject(e)
        return
      }

      val errorCode: Int = this.vpnPlugin.startVpnTunnel(tunnelId, config)
      if (errorCode !== OutlinePlugin.ErrorCode.NO_ERROR.value) {
        promise.resolve(true)
        return
      }
      promise.reject(errorCode.toString(), "Start failed")
    }

    @ReactMethod
    fun stopTunnel(tunnelId: String, promise: Promise) {
      val errorCode: Int = this.vpnPlugin.vpnTunnelService.stopTunnel(tunnelId)
      if (errorCode !== OutlinePlugin.ErrorCode.NO_ERROR.value) {
        promise.resolve(true)
        return
      }
      promise.reject(errorCode.toString(), "Stop failed")
    }

  @ReactMethod
  fun isRunning(tunnelId: String, promise: Promise) {
    val isActive: Boolean = this.vpnPlugin.isTunnelActive(tunnelId)
    promise.resolve(isActive)
  }

  @ReactMethod
  fun isReachable(tunnelId: String, promise: Promise) {
    val isActive: Boolean = ShadowsocksConnectivity.isServerReachable(tunnelId)
    promise.resolve(isActive)
  }
}
