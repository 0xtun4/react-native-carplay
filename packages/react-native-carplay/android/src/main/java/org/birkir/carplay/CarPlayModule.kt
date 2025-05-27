package org.birkir.carplay

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.ScreenManager
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.debug.DevSettingsModule
import org.birkir.carplay.parser.Parser
import org.birkir.carplay.parser.TemplateParser
import org.birkir.carplay.screens.CarScreen
import org.birkir.carplay.screens.CarScreenContext
import org.birkir.carplay.utils.CarNavigationManager
import org.birkir.carplay.utils.EventEmitter
import java.util.WeakHashMap


@ReactModule(name = CarPlayModule.NAME)
class CarPlayModule internal constructor(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  private lateinit var carContext: CarContext
  private lateinit var parser: Parser

  private var screenManager: ScreenManager? = null
  private val carScreens: WeakHashMap<String, CarScreen> = WeakHashMap()
  private val carScreenContexts: WeakHashMap<CarScreen, CarScreenContext> =
    WeakHashMap()
  val clusterScreens: WeakHashMap<CarScreen, CarScreenContext> = WeakHashMap()
  private val handler: Handler = Handler(Looper.getMainLooper())

  // Global event emitter (no templateId's)
  private var eventEmitter = EventEmitter(reactContext)

  init {
    reactContext.addLifecycleEventListener(object : LifecycleEventListener {
      override fun onHostResume() {
        reactContext.getNativeModule(DevSettingsModule::class.java)
          ?.addMenuItem("Reload Android Auto")
      }

      override fun onHostPause() {}
      override fun onHostDestroy() {}
    })
  }

  override fun getName(): String {
    return NAME
  }

  fun setCarContext(carContext: CarContext, currentCarScreen: CarScreen) {
    parser = Parser(carContext, CarScreenContext("", eventEmitter, carScreens))
    this.carContext = carContext
    screenManager = currentCarScreen.screenManager
    carScreens["root"] = currentCarScreen
    carContext.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        val topScreen = screenManager?.top
        if (topScreen is CarScreen) {
          // handle back key only for rn-cp screens
          eventEmitter.backButtonPressed(screenManager?.top?.marker)
          return
        }

        isEnabled = false
        carContext.onBackPressedDispatcher.onBackPressed()
        isEnabled = true

      }
    })
    eventEmitter.didConnect()
  }

  private fun parseTemplate(
    config: ReadableMap,
    carScreenContext: CarScreenContext
  ): Template {
    val factory = TemplateParser(carContext, carScreenContext)
    return factory.parse(config)
  }

  @ReactMethod
  fun checkForConnection() {
    eventEmitter.didConnect()
  }

  @ReactMethod
  fun createTemplate(templateId: String, config: ReadableMap, callback: Callback?) {
    handler.post {
      Log.d(TAG, "Creating template $templateId")

      try {
        createScreen(templateId, config)
        callback?.invoke()
      } catch (err: IllegalArgumentException) {
        val args = Arguments.createMap()
        args.putString("error", "Failed to parse template '$templateId': ${err.message}")
        callback?.invoke(args)
      }
    }
  }

  @ReactMethod
  fun updateTemplate(templateId: String, config: ReadableMap) {
    handler.post {
      carScreens[templateId]?.let { screen ->
        carScreenContexts[screen]?.let { carScreenContext ->
          val template = parseTemplate(config, carScreenContext)
          val isSurfaceTemplate = config.hasKey("render") && config.getBoolean("render")
          screen.setTemplate(template = template, invalidate = true, isSurfaceTemplate = isSurfaceTemplate)

          if (template is NavigationTemplate) {
            clusterScreens.filter { it.key.template is NavigationTemplate }.forEach{
              val clusterTemplate = parseTemplate(config, it.value)
              // cluster can hold NavigationTemplate only and always has a surface to render to
              it.key.setTemplate(template = clusterTemplate, invalidate = true, isSurfaceTemplate = true)
            }
          }
        }
      }
    }
  }

  @ReactMethod
  fun setRootTemplate(templateId: String, animated: Boolean?) {
    Log.d(TAG, "set Root Template for $templateId")
    handler.post {
      screenManager?.let {
        val screen = getScreen(templateId)
        if (screen != null) {
          it.popToRoot()
          val root = it.top
          it.push(screen)

          if (screen.template is NavigationTemplate) {
            CarNavigationManager.init(
              carContext = carContext,
              eventEmitter = EventEmitter(reactContext = reactContext, templateId = templateId)
            )
          }

          screen.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
              eventEmitter.didPopToRoot()
            }

            override fun onDestroy(owner: LifecycleOwner) {
              screen.lifecycle.removeObserver(this)
            }
          })

          if (root is CarScreen) {
            removeScreen(root)
          }
          it.remove(root)
        }
      }
    }
  }

  @ReactMethod
  fun pushTemplate(templateId: String, animated: Boolean?) {
    handler.post {
      val screen = getScreen(templateId)
      if (screen != null) {
        screenManager?.push(screen)
      }
    }
  }

  @ReactMethod
  fun popToTemplate(templateId: String, animated: Boolean?) {
    handler.post {
      screenManager?.popTo(templateId)
    }
  }

  @ReactMethod
  fun popTemplate(animated: Boolean?) {
    handler.post {
      screenManager!!.pop()
      val topScreen = screenManager?.top
      if (topScreen != null && topScreen is CarScreen) {
        topScreen.invalidate()
      }
    }
  }

  @ReactMethod
  fun presentTemplate(templateId: String?, animated: Boolean?) {
    // void
  }

  @ReactMethod
  fun dismissTemplate(templateId: String?, animated: Boolean?) {
    // void
  }

  // pragma: Android Auto only stuff

  @ReactMethod
  fun toast(text: String, duration: Int) {
    CarToast.makeText(carContext, text, duration).show()
  }

  @ReactMethod
  fun alert(props: ReadableMap) {
    handler.post {
      val id = props.getInt("id")
      val title = parser.parseCarText(props.getString("title")!!, props)
      val duration = props.getInt("duration").toLong()
      val alert = Alert.Builder(id, title, duration).apply {
        setCallback(object : AlertCallback {
          override fun onCancel(reason: Int) {
            val reasonString = when (reason) {
              AlertCallback.REASON_TIMEOUT -> "timeout"
              AlertCallback.REASON_USER_ACTION -> "userAction"
              AlertCallback.REASON_NOT_SUPPORTED -> "notSupported"
              else -> "unknown"
            }
            eventEmitter.alertActionPressed("cancel", reasonString)
          }
          override fun onDismiss() {
            eventEmitter.alertActionPressed("dismiss" )
          }
        })
        props.getString("subtitle")?.let { setSubtitle(parser.parseCarText(it, props)) }
        props.getMap("image")?.let { setIcon(parser.parseCarIcon(it)) }
        props.getArray("actions")?.let {
          for (i in 0 until it.size()) {
            addAction(parser.parseAction(it.getMap(i)))
          }
        }
      }.build()
      carContext.getCarService(AppManager::class.java).showAlert(alert)
    }
  }

  @ReactMethod
  fun dismissAlert(alertId: Int) {
    carContext.getCarService(AppManager::class.java).dismissAlert(alertId)
  }

  @ReactMethod
  fun invalidate(templateId: String) {
    handler.post {
      val screen = getScreen(templateId) ?: return@post
      if (screen === screenManager!!.top) {
        Log.d(TAG, "Invalidated screen $templateId")
        screen.invalidate()
      }
    }
  }

  @ReactMethod
  fun reload() {
    val intent = Intent("org.birkir.carplay.APP_RELOAD")
    reactContext.sendBroadcast(intent)
  }

  @ReactMethod
  fun getHostInfo(promise: Promise) {
    return promise.resolve(Arguments.createMap().apply {
      carContext.hostInfo?.packageName?.let { putString("packageName", it) }
      carContext.hostInfo?.uid?.let { putInt("uid", it) }
    })
  }

  // Others

  @ReactMethod
  fun addListener(eventName: String) {
    Log.d(TAG, "listener added $eventName")
  }

  @ReactMethod
  fun removeListeners(count: Int) {
    Log.d(TAG, "remove listeners $count")
  }

  @ReactMethod
  fun checkForDashboardConnection() {
    //TODO
  }

  @ReactMethod
  fun createDashboard(dashboardId: String, config: ReadableMap) {
    //TODO
  }

  @ReactMethod
  fun updateDashboardShortcutButtons(config: ReadableMap) {
    //TODO
  }

  @ReactMethod
  fun updateListTemplateSections(templateId: String, config: ReadableArray) {
    //TODO
  }

  @ReactMethod
  fun updateListTemplateItem(templateId: String, config: ReadableMap) {
    //TODO
  }

  @ReactMethod
  fun getMaximumListItemCount(templateId: String) {
    //TODO
  }

  @ReactMethod
  fun getMaximumListItemImageSize(templateId: String) {
    //TODO
  }

  @ReactMethod
  fun getMaximumNumberOfGridImages(templateId: String) {
    //TODO
  }

  @ReactMethod
  fun getMaximumListImageRowItemImageSize(templateId: String) {
    //TODO
  }

  @ReactMethod
  fun popToRootTemplate(animated: Boolean?) {
    handler.post {
      screenManager?.popToRoot()
    }
  }

  private fun createCarScreenContext(screen: CarScreen, emitter: EventEmitter): CarScreenContext {
    val templateId = screen.marker!!
    return CarScreenContext(templateId, emitter, carScreens)
  }

  private fun createScreen(templateId: String, templateConfig: ReadableMap?): CarScreen? {
    if (templateConfig != null) {
      val emitter = EventEmitter(reactContext, templateId)
      val screen = CarScreen(carContext, emitter)
      screen.marker = templateId

      // context
      carScreenContexts.remove(screen)
      val carScreenContext = createCarScreenContext(screen, emitter)
      carScreenContexts[screen] = carScreenContext

      val template = parseTemplate(templateConfig, carScreenContext)
      val isSurfaceTemplate = templateConfig.hasKey("render") && templateConfig.getBoolean("render")
      screen.setTemplate(template = template, isSurfaceTemplate = isSurfaceTemplate)
      carScreens[templateId] = screen

      return screen
    }
    return null
  }

  private fun getScreen(name: String): CarScreen? {
    return carScreens[name] ?: createScreen(name, null)
  }

  private fun removeScreen(screen: CarScreen?) {
    val params = WritableNativeMap()
    params.putString("screen", screen!!.marker)
    carScreens.values.remove(screen)
  }

  companion object {
    const val NAME = "RNCarPlay"
    const val TAG = "CarPlay"
    const val APP_RELOAD = "org.birkir.carplay.AppReload"
  }
}
