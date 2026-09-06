package com.p2r3.convert.pampai

import com.p2r3.convert.ConvertApplication
import com.p2r3.convert.engine.EngineStatus
import dev.antigravity.fluidengine.ai.bridge.AiToolHostProvider
import dev.antigravity.fluidengine.ai.bridge.ReadyState
import dev.antigravity.fluidengine.ai.bridge.RemoteCall
import dev.antigravity.fluidengine.ai.tools.ToolRegistry

/**
 * Il convertitore visto da PampAI/Aria: i nove strumenti girano qui, dove c'e' il motore WASM.
 * Permesso `signature`, come per le altre app Pampa.
 *
 * "Pronto" e' sempre vero di proposito: il motore si accende alla prima chiamata (il servizio in
 * primo piano lo tiene su), e rispondere "non pronto" costringerebbe l'utente ad aprire l'app per
 * una cosa che l'app sa fare da sola.
 */
class ConvertToolHostProvider : AiToolHostProvider<ConvertToolContext>() {

  private val registry by lazy { ConvertTools.registry() }

  override fun registry(): ToolRegistry<ConvertToolContext> = registry

  override suspend fun context(call: RemoteCall): ConvertToolContext =
    ConvertToolContext(context!!.applicationContext as ConvertApplication, context!!)

  override fun domain(): String = "convert"

  override fun appLabel(): String = "Convert to it!"

  override fun routerHint(): String =
    "convertire file da un formato a un altro (immagini, documenti, audio, video, archivi): quali formati esistono, cosa si puo' ottenere da un file, la conversione vera e propria"

  /** Le famiglie di formati: le parole con cui l'utente nomina una conversione. */
  override fun vocabulary(): List<String> = runCatching {
    val app = context!!.applicationContext as ConvertApplication
    app.engine.formats.value.asSequence().map { it.format }.distinct().take(120).toList()
  }.getOrDefault(emptyList())

  override fun ready(): ReadyState {
    val app = context?.applicationContext as? ConvertApplication ?: return ReadyState(false, "l'app non e' pronta")
    return when (app.engine.status.value) {
      EngineStatus.Broken -> ReadyState(false, "il motore di conversione e' guasto: apri Convert to it! una volta per farlo ripartire")
      else -> ReadyState(true)
    }
  }

  override fun partsAuthority(): String? = "${context?.packageName}.fileprovider"
}
