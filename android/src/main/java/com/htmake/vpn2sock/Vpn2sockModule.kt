package com.htmake.vpn2sock

import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.IBinder
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import org.json.JSONObject
import org.outline.IVpnTunnelService
import org.outline.OutlinePlugin
import org.outline.OutlinePlugin.MessageData
import org.outline.OutlinePlugin.TunnelStatus
import org.outline.TunnelConfig
import org.outline.shadowsocks.ShadowsocksConnectivity
import org.outline.vpn.VpnServiceStarter
import org.outline.vpn.VpnTunnelService
import java.util.*
import java.util.logging.Level


class Vpn2sockModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private var vpnPlugin: OutlinePlugin;
    private var mStartVpnRequest: StartVpnRequest? = null;
    private class StartVpnRequest(val tunnelId: String, val config: ReadableMap, val promise: Promise)

    private val REQUEST_CODE_PREPARE_VPN = 100

    private val mActivityEventListener: ActivityEventListener = object : BaseActivityEventListener() {
      override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (mStartVpnRequest != null && requestCode == REQUEST_CODE_PREPARE_VPN) {
          if (resultCode == Activity.RESULT_CANCELED) {
            mStartVpnRequest!!.promise.reject(OutlinePlugin.ErrorCode.VPN_PERMISSION_NOT_GRANTED.value.toString(), "VPN permission was not granted")
          } else if (resultCode == RESULT_OK) {
            startTunnel(mStartVpnRequest!!.tunnelId, mStartVpnRequest!!.config, mStartVpnRequest!!.promise)
          } else {
            mStartVpnRequest!!.promise.reject(OutlinePlugin.ErrorCode.VPN_PERMISSION_NOT_GRANTED.value.toString(), "VPN未添加成功")
          }
        }
      }
    }

    // Broadcasts
    private val vpnTunnelBroadcastReceiver = VpnTunnelBroadcastReceiver(reactContext)

    // Receiver to forward VPN service broadcasts to the WebView when the tunnel status changes.
    class VpnTunnelBroadcastReceiver(private val rContext: ReactApplicationContext) : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val tunnelId = intent.getStringExtra(MessageData.TUNNEL_ID.value)
        val status = intent.getIntExtra(MessageData.PAYLOAD.value, TunnelStatus.INVALID.value)
        OutlinePlugin.LOG.fine(String.format(Locale.ROOT, "VPN connectivity changed: %s, %d", tunnelId
          ?: "UNKNOW", status))

        val params = Arguments.createMap()
        params.putString("tunnelId", tunnelId ?: "")
        params.putInt("status", status)
        rContext.getJSModule(RCTDeviceEventEmitter::class.java)
          .emit("onVPNStatusChange", params)
      }
    }

    // AIDL interface for VpnTunnelService, which is bound for the lifetime of this class.
    // The VpnTunnelService runs in a sub process and is thread-safe.
    // A race condition may occur when calling methods on this instance if the service unbinds.
    // We catch any exceptions, which should generally be transient and recoverable, and report them
    // to the WebView.
    var vpnTunnelService: IVpnTunnelService? = null


    private val vpnServiceConnection: ServiceConnection = object : ServiceConnection {
      override fun onServiceConnected(className: ComponentName, binder: IBinder) {
        vpnTunnelService = IVpnTunnelService.Stub.asInterface(binder)
        OutlinePlugin.LOG.info("VPN service connected")
      }

      override fun onServiceDisconnected(className: ComponentName) {
        OutlinePlugin.LOG.warning("VPN service disconnected")
        // Rebind the service so the VPN automatically reconnects if the service process crashed.
        val context: Context = reactContext.getApplicationContext()
        val rebind = Intent(context, VpnTunnelService::class.java)
        rebind.putExtra(VpnServiceStarter.AUTOSTART_EXTRA, false)
        // Send the error reporting API key so the potential crash is reported.
        context.bindService(rebind, this, Context.BIND_AUTO_CREATE)
      }
    }


    init {
      this.vpnPlugin = OutlinePlugin()
      this.reactContext.addActivityEventListener(mActivityEventListener)
      val context: Context = reactContext.getApplicationContext()
      val broadcastFilter = IntentFilter()
      broadcastFilter.addAction(OutlinePlugin.Action.ON_STATUS_CHANGE.value)
      broadcastFilter.addCategory(context.packageName)
      context.registerReceiver(vpnTunnelBroadcastReceiver, broadcastFilter)

      context.bindService(Intent(context, VpnTunnelService::class.java), vpnServiceConnection,
        Context.BIND_AUTO_CREATE)
    }

    override fun getName(): String {
        return "Vpn2sock"
    }

    @ReactMethod
    fun startTunnel(tunnelId: String, config: ReadableMap, promise: Promise) {
      try {
        mStartVpnRequest = StartVpnRequest(tunnelId, config, promise)
        if (!this.prepareVpnService()) {
          // 等待VPN添加
          return
        }
      } catch (e: ActivityNotFoundException) {
        promise.reject(e)
        return
      }
      mStartVpnRequest = null

      if (vpnTunnelService == null) {
        promise.reject(OutlinePlugin.ErrorCode.UNEXPECTED.toString(), "VPNService not connected")
        return
      }

      var defaultConfig: JSONObject = JSONObject("{}")
      var jconfig = this.convertToJSONObject(config)
      val tunnelConfig: TunnelConfig

      OutlinePlugin.LOG.info(String.format(Locale.ROOT, "Starting VPN tunnel %s", tunnelId))
      tunnelConfig = try {
        VpnTunnelService.makeTunnelConfig(tunnelId, jconfig ?: defaultConfig)
      } catch (e: Exception) {
        OutlinePlugin.LOG.log(Level.SEVERE, "Failed to retrieve the tunnel proxy config.", e)
        promise.reject(OutlinePlugin.ErrorCode.ILLEGAL_SERVER_CONFIGURATION.value.toString(), "Failed to retrieve the tunnel proxy config.")
        return
      }

      val errorCode: Int = vpnTunnelService!!.startTunnel(tunnelConfig)
      if (errorCode == OutlinePlugin.ErrorCode.NO_ERROR.value) {
        promise.resolve(true)
        return
      }
      promise.reject(errorCode.toString(), "Start failed")
    }

    @ReactMethod
    fun stopTunnel(tunnelId: String, promise: Promise) {
      if (vpnTunnelService == null) {
        promise.reject(OutlinePlugin.ErrorCode.UNEXPECTED.toString(), "VPNService not connected")
        return
      }
      val errorCode: Int = vpnTunnelService!!.stopTunnel(tunnelId)
      if (errorCode == OutlinePlugin.ErrorCode.NO_ERROR.value || errorCode == OutlinePlugin.ErrorCode.UNEXPECTED.value) {
        promise.resolve(true)
        return
      }
      promise.reject(errorCode.toString(), "Stop failed")
    }

    @ReactMethod
    fun getActivedTunnelId(promise: Promise) {
      if (vpnTunnelService == null) {
        promise.reject(OutlinePlugin.ErrorCode.UNEXPECTED.toString(), "VPNService not connected")
        return
      }
      val tunnelId: String = vpnTunnelService!!.getActivedTunnelId()
      promise.resolve(tunnelId)
    }

    @ReactMethod
    fun getTunnelStatus(promise: Promise) {
      if (vpnTunnelService == null) {
        promise.reject(OutlinePlugin.ErrorCode.UNEXPECTED.toString(), "VPNService not connected")
        return
      }
      val status: Int = vpnTunnelService!!.getTunnelStatus()
      promise.resolve(status)
    }

    @ReactMethod
    fun isRunning(tunnelId: String, promise: Promise) {
      if (vpnTunnelService == null) {
        promise.reject(OutlinePlugin.ErrorCode.UNEXPECTED.toString(), "VPNService not connected")
        return
      }
      var isActive = false
      try {
        isActive = vpnTunnelService!!.isTunnelActive(tunnelId)
      } catch (e: Exception) {
        OutlinePlugin.LOG.log(Level.SEVERE, String.format(Locale.ROOT, "Failed to determine if tunnel is active: %s", tunnelId), e)
      }
      promise.resolve(isActive)
    }

    @ReactMethod
    fun isReachable(host: String, port: Int, promise: Promise) {
      val isReachable: Boolean = ShadowsocksConnectivity.isServerReachable(host, port)
      promise.resolve(isReachable)
    }

    @ReactMethod
    fun getPackageList(promise: Promise) {
      val packages = Arguments.createArray()
      val packageManager: PackageManager = this.reactContext.getApplicationContext().getPackageManager()
      try {
        val packageInfos: List<PackageInfo> = packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES or
          PackageManager.GET_SERVICES)
        for (info in packageInfos) {
          val packageInfo = Arguments.createMap()
          packageInfo.putString("appName", info.applicationInfo.loadLabel(packageManager) as String)
          packageInfo.putString("packageName", info.packageName)
          packageInfo.putInt("versionCode", info.versionCode)
          packageInfo.putString("versionName", info.versionName)
          packages.pushMap(packageInfo)
        }
      } catch (t: Throwable) {
        t.printStackTrace()
      }
      promise.resolve(packages)
    }

    @Throws(ActivityNotFoundException::class)
    fun prepareVpnService(): Boolean {
      OutlinePlugin.LOG.fine("Preparing VPN.")
      val prepareVpnIntent = VpnService.prepare(this.reactContext.getApplicationContext()) ?: return true
      OutlinePlugin.LOG.info("Prepare VPN with activity")
      currentActivity!!.startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN)
      return false
    }

  /**
   * ReadableMap 转换成 JSONObject
   */
  fun convertToJSONObject(properties: ReadableMap?): JSONObject? {
    if (properties == null) {
      return null
    }
    var json: JSONObject? = null
    var nativeMap: ReadableNativeMap? = null
    try {
      nativeMap = properties as ReadableNativeMap?
      json = JSONObject(properties.toString()).getJSONObject("NativeMap")
    } catch (e: Exception) {
      OutlinePlugin.LOG.info(e.toString())
      val superName = nativeMap!!.javaClass.getSuperclass()!!.simpleName
      try {
        json = JSONObject(properties.toString()).getJSONObject(superName)
      } catch (e1: Exception) {
        OutlinePlugin.LOG.info(e1.toString())
      }
    }
    return json
  }

  /**
   * JSONObject 转换成 WritableMap
   */
  fun convertToMap(json: JSONObject?): WritableMap? {
    if (json == null || json.length() == 0) {
      return null
    }
    val writableMap = Arguments.createMap()
    val it = json.keys()
    while (it.hasNext()) {
      try {
        val key = it.next()
        writableMap.putString(key, json.optString(key))
      } catch (e: Exception) {
        OutlinePlugin.LOG.info(e.toString())
      }
    }
    return writableMap
  }

}
