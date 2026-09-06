package com.p2r3.convert.pampai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.p2r3.convert.ConvertApplication
import com.p2r3.convert.MainActivity
import com.p2r3.convert.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tiene in piedi il motore mentre una conversione chiesta da fuori (PampAI/Aria) lavora, con l'app
 * chiusa.
 *
 * Il motore vive in una WebView: senza una schermata che la ospiti, la pagina non viene disposta e
 * gli handler che disegnano il DOM non hanno una superficie su cui lavorare. Qui la WebView si
 * crea lo stesso e si misura a mano a 1080x1920 — non e' un vetro montato in una finestra, ma per
 * la gran parte dei formati (immagini, documenti, audio, archivi, che passano da WASM e non dal
 * DOM) e' abbastanza. Quelli che pretendono davvero una pagina vera falliscono con un errore che
 * lo dice, e il tool risponde "apri l'app".
 *
 * Si spegne da solo quando non resta piu' niente da convertire.
 */
class ConversionService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var watcher: Job? = null
  private var host: FrameLayout? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val manager = getSystemService(NotificationManager::class.java)
    manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Conversioni", NotificationManager.IMPORTANCE_LOW).apply { description = "Le conversioni chieste dall'assistente" })
    val notification = build("Conversione in corso…")
    if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIFICATION_ID, notification)
    ensureEngine()
    watcher?.cancel()
    watcher = scope.launch {
      // Un attimo di grazia: il tool ha appena chiesto la conversione e il lavoro puo' non essere
      // ancora partito. Poi si resta finche' c'e' qualcosa da fare.
      delay(GRACE_MILLIS)
      val engine = (application as ConvertApplication).engine
      while (engine.jobsInFlight > 0) delay(500)
      stopSelf()
    }
    return START_NOT_STICKY
  }

  /**
   * La WebView del motore, montata fuori da qualsiasi schermata: misurata e disposta a mano,
   * perche' una view mai layoutata non disegna niente e certi handler se ne accorgono.
   */
  private fun ensureEngine() {
    val app = application as ConvertApplication
    if (app.engine.isRunning) return
    val view = app.engine.createWebView(this)
    val container = FrameLayout(this).apply {
      layoutParams = FrameLayout.LayoutParams(WIDTH, HEIGHT)
      addView(view, FrameLayout.LayoutParams(WIDTH, HEIGHT))
    }
    container.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
    container.layout(0, 0, WIDTH, HEIGHT)
    host = container
  }

  private fun build(text: String): Notification = Notification.Builder(this, CHANNEL)
    .setSmallIcon(R.mipmap.ic_launcher)
    .setContentTitle("Convert to it!")
    .setContentText(text)
    .setOngoing(true)
    .setOnlyAlertOnce(true)
    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    .build()

  override fun onDestroy() {
    watcher?.cancel()
    host?.removeAllViews()
    host = null
    scope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "conversions"
    private const val NOTIFICATION_ID = 4301
    private const val GRACE_MILLIS = 2_000L

    /** La misura di una pagina vera: gli handler che guardano il viewport trovano numeri sensati. */
    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    @Suppress("unused")
    private val keepWindowManagerImport = WindowManager.LayoutParams.TYPE_APPLICATION
  }
}
