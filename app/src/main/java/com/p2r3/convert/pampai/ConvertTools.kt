package com.p2r3.convert.pampai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.p2r3.convert.ConvertApplication
import com.p2r3.convert.MainActivity
import com.p2r3.convert.engine.ConversionEngine
import com.p2r3.convert.engine.ConversionResult
import com.p2r3.convert.engine.ConversionStage
import com.p2r3.convert.engine.ConvertedFile
import com.p2r3.convert.engine.EngineStatus
import com.p2r3.convert.engine.FailureReason
import com.p2r3.convert.engine.FormatOption
import com.p2r3.convert.engine.InputFile
import com.p2r3.convert.data.formatSize
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.bool
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.antigravity.fluidengine.ai.tools.ToolText
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Cosa un tool del convertitore ha in mano: il motore, i file, il contesto Android. */
class ConvertToolContext(val app: ConvertApplication, val context: Context) {
  val engine: ConversionEngine get() = app.engine
}

/** Un gruppo solo: il convertitore fa una cosa, e per il router e' una porta sola. */
enum class ConvertToolGroup(override val id: String, override val statusKey: String, override val hint: String) : AiToolGroup {
  CONVERT("convert", "convert", "convertire file da un formato a un altro: quali formati esistono, cosa si puo' ottenere da un file, convertire, seguire una conversione, aprire il convertitore"),
}

private const val NOT_READY = "il motore di conversione non e' ancora pronto: si accende da solo, riprova fra qualche secondo (o apri Convert to it! una volta)"

/** L'ultimo risultato, per `condividi_convertito`: vive quanto il processo. */
internal object LastConversion {
  @Volatile var files: List<ConvertedFile> = emptyList()
}

/** I lavori avviati dall'assistente, per `stato_conversione` e `annulla_conversione`. */
internal object BridgeJobsRegistry {
  class Entry(val label: String) {
    @Volatile var stage: String = "in preparazione"
    @Volatile var progress: Float = 0f
    @Volatile var done: String? = null
    @Volatile var failed: String? = null
  }

  private val entries = ConcurrentHashMap<String, Entry>()

  fun start(label: String): Pair<String, Entry> {
    val id = (entries.size + 1).toString()
    val entry = Entry(label)
    entries[id] = entry
    return id to entry
  }

  fun get(id: String?): Entry? = id?.let { entries[it] }

  fun latest(): Pair<String, Entry>? = entries.entries.maxByOrNull { it.key.toIntOrNull() ?: 0 }?.let { it.key to it.value }

  fun all(): Map<String, Entry> = entries.toMap()
}

/** Aspetta che il motore sia acceso: lo accende il servizio, e la prima volta ci mette qualche secondo. */
private suspend fun ConvertToolContext.awaitEngine(timeoutMillis: Long = 25_000): Boolean {
  if (engine.status.value == EngineStatus.Ready) return true
  startService()
  return withTimeoutOrNull(timeoutMillis) { engine.status.first { it == EngineStatus.Ready } } != null
}

private fun ConvertToolContext.startService() {
  val intent = Intent(context, ConversionService::class.java)
  runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent) }
}

private fun FormatOption.line(): String =
  "$format · $name · .$extension · $mime" + (if (lossless) " · senza perdita" else "") + (categories.firstOrNull()?.let { " · $it" } ?: "")

class FormatSearchTool : AiTool<ConvertToolContext> {
  override val name = "formato_cerca"
  override val group: AiToolGroup = ConvertToolGroup.CONVERT
  override val description = "Cerca un formato di file fra quelli che il convertitore conosce (per nome, estensione o tipo MIME) e dice se si puo' usare come partenza, come arrivo, o entrambi."
  override val parameters = Schema.obj(mapOf("cosa" to Schema.str("nome, estensione o tipo del formato, es. \"png\", \"documento word\", \"audio\"")), required = listOf("cosa"))

  override suspend fun run(args: JsonObject, ctx: ConvertToolContext): ToolOutput {
    if (!ctx.awaitEngine()) return ToolOutput.error(NOT_READY)
    val query = args.str("cosa")?.lowercase()?.trim() ?: return ToolOutput.error("dimmi cosa cercare")
    val all = ctx.engine.formats.value
    val hits = all.filter { it.searchIndex.contains(query) }.distinctBy { it.format to it.mime }
    if (hits.isEmpty()) return ToolText.output { line("formati", "nessuno che somigli a \"$query\" fra i ${all.size} conosciuti") }
    return ToolText.output(3_000) {
      line("formati trovati", hits.size)
      hits.take(25).forEach { f ->
        line("${f.line()} · " + listOfNotNull("partenza".takeIf { _ -> f.from }, "arrivo".takeIf { _ -> f.to }).joinToString(" e ").ifEmpty { "solo interno" })
      }
      if (hits.size > 25) line("altri", hits.size - 25)
    }
  }
}

class PossibleConversionsTool : AiTool<ConvertToolContext> {
  override val name = "conversioni_possibili"
  override val group: AiToolGroup = ConvertToolGroup.CONVERT
  override val description = "In quali formati si puo' convertire un file di partenza (per estensione o nome del formato). Elenca i formati di arrivo, prima quelli della stessa famiglia."
  override val parameters = Schema.obj(mapOf("da" to Schema.str("il formato di partenza, es. \"png\", \"pdf\", \"docx\"")), required = listOf("da"))

  override suspend fun run(args: JsonObject, ctx: ConvertToolContext): ToolOutput {
    if (!ctx.awaitEngine()) return ToolOutput.error(NOT_READY)
    val query = args.str("da")?.lowercase()?.trim()?.removePrefix(".") ?: return ToolOutput.error("dimmi il formato di partenza")
    val all = ctx.engine.formats.value
    val source = all.firstOrNull { it.from && (it.extension.lowercase() == query || it.format.lowercase() == query) }
      ?: all.firstOrNull { it.from && it.searchIndex.contains(query) }
      ?: return ToolOutput.error("\"$query\" non risulta un formato di partenza: cercalo con formato_cerca")
    val targets = all.filter { it.to && it.format != source.format }.distinctBy { it.format to it.mime }
    val sameFamily = targets.filter { it.group == source.group }
    val others = targets - sameFamily.toSet()
    return ToolText.output(3_000) {
      line("da", source.line())
      line("formati di arrivo", targets.size)
      if (sameFamily.isNotEmpty()) line("stessa famiglia (${source.group})", sameFamily.joinToString(", ") { it.format })
      if (others.isNotEmpty()) line("altre famiglie", others.take(60).joinToString(", ") { it.format })
      line("nota", "il percorso vero fra due formati lo trova il motore al momento della conversione: se non esiste, converti_file lo dice")
    }
  }
}

class ConvertFileTool : AiTool<ConvertToolContext> {
  override val name = "converti_file"
  override val group: AiToolGroup = ConvertToolGroup.CONVERT
  override val description = "Converte un file in un altro formato e lo salva in Download/Convert. Il file si indica per nome (viene cercato in Download e Download/Convert) o con il suo indirizzo content://. Puo' durare: il risultato arriva quando e' pronto."
  override val parameters = Schema.obj(
    mapOf(
      "file" to Schema.str("il nome del file (es. \"foto.png\") o il suo indirizzo content://"),
      "a" to Schema.str("il formato di arrivo, es. \"jpg\", \"pdf\", \"webp\""),
      "semplice" to Schema.bool("true per la conversione diretta senza passaggi intermedi (default false)"),
    ),
    required = listOf("file", "a"),
  )
  override val longRunning = true
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: ConvertToolContext): ToolOutput {
    if (!ctx.awaitEngine()) return ToolOutput.error(NOT_READY)
    val fileArg = args.str("file") ?: return ToolOutput.error("dimmi quale file")
    val targetArg = args.str("a")?.lowercase()?.trim()?.removePrefix(".") ?: return ToolOutput.error("dimmi il formato di arrivo")
    val formats = ctx.engine.formats.value
    val target = formats.firstOrNull { it.to && (it.extension.lowercase() == targetArg || it.format.lowercase() == targetArg) }
      ?: formats.firstOrNull { it.to && it.searchIndex.contains(targetArg) }
      ?: return ToolOutput.error("\"$targetArg\" non e' un formato di arrivo che conosco: cercalo con formato_cerca")

    val (uri, displayName) = resolveInput(ctx, fileArg) ?: return ToolOutput.error(
      "non trovo il file \"$fileArg\". Mettilo nella cartella Download, oppure aprilo dall'app: apri_convertitore",
    )
    val extension = displayName.substringAfterLast('.', "").lowercase()
    val source = formats.firstOrNull { it.from && it.extension.lowercase() == extension }
      ?: return ToolOutput.error("non riconosco il formato di \"$displayName\" (estensione .$extension): il convertitore non lo tratta come file di partenza")

    val (jobId, entry) = BridgeJobsRegistry.start("$displayName → ${target.format}")
    ctx.startService()
    val result = ctx.engine.convert(
      inputs = listOf(InputFile(displayName, uri)),
      fromId = source.id,
      toId = target.id,
      simpleMode = args.bool("semplice") == true,
    ) { progress ->
      entry.stage = when (progress.stage) {
        ConversionStage.Preparing -> "preparazione"
        ConversionStage.Searching -> "cerco un percorso fra i formati"
        ConversionStage.Downloading -> "scarico i pezzi del motore"
        ConversionStage.Converting -> "converto"
        ConversionStage.Writing -> "scrivo il risultato"
      }
      entry.progress = progress.fraction() ?: entry.progress
    }
    return when (result) {
      is ConversionResult.Success -> {
        LastConversion.files = result.files
        entry.done = result.files.joinToString(", ") { it.name }
        val saved = result.files.mapNotNull { file -> ctx.app.fileGateway.saveToDownloads(file)?.let { file } }
        ToolText.output {
          line("fatto", "convertito in ${target.format}")
          result.files.forEach { line("file", "${it.name} · ${formatSize(it.size)}") }
          line("salvato in", if (saved.isEmpty()) "solo nella cache dell'app (il salvataggio in Download non e' riuscito)" else "Download/Convert")
          if (result.path.size > 2) line("percorso", result.path.joinToString(" → "))
          line("id conversione", jobId)
        }
      }
      is ConversionResult.Failure -> {
        entry.failed = result.detail.ifBlank { result.reason.name }
        ToolOutput.error(
          when (result.reason) {
            FailureReason.NoPath -> "non c'e' un modo per passare da ${source.format} a ${target.format}"
            FailureReason.Cancelled -> "conversione annullata"
            FailureReason.Download -> "non sono riuscito a scaricare i pezzi del motore che servono: serve rete"
            FailureReason.Engine -> "il motore non e' riuscito a convertire${result.detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}. Alcuni formati hanno bisogno dell'app aperta: apri_convertitore"
          },
        )
      }
    }
  }

  /** Il file: un content:// dato dal chiamante, o un nome cercato in Download e Download/Convert. */
  private fun resolveInput(ctx: ConvertToolContext, raw: String): Pair<Uri, String>? {
    if (raw.startsWith("content://") || raw.startsWith("file://")) {
      val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
      val described = runCatching { ctx.app.fileGateway.describe(uri) }.getOrNull() ?: return null
      return uri to described.name.ifBlank { raw.substringAfterLast('/') }
    }
    val name = raw.substringAfterLast('/')
    val roots = listOfNotNull(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
      File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Convert"),
      ctx.context.getExternalFilesDir(null),
    )
    val file = roots.asSequence()
      .mapNotNull { root -> root.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) } ?: root.listFiles()?.firstOrNull { it.isFile && it.name.contains(name, ignoreCase = true) } }
      .firstOrNull() ?: return null
    return Uri.fromFile(file) to file.name
  }
}

class ConversionStatusTool : AiTool<ConvertToolContext> {
  override val name = "stato_conversione"
  override val group: AiToolGroup = ConvertToolGroup.CONVERT
  override val description = "A che punto e' una conversione avviata (l'ultima, o quella con un id)."
  override val parameters = Schema.obj(mapOf("id" to Schema.str("l'id della conversione; vuoto per l'ultima")))

  override suspend fun run(args: JsonObject, ctx: ConvertToolContext): ToolOutput {
    val entry = args.str("id")?.let { id -> BridgeJobsRegistry.get(id)?.let { id to it } } ?: BridgeJobsRegistry.latest()
      ?: return ToolText.output { line("conversioni", "nessuna avviata dall'assistente in questa sessione") }
    val (id, job) = entry
    return ToolText.output {
      line("conversione", "#$id ${job.label}")
      when {
        job.done != null -> line("stato", "finita: ${job.done}")
        job.failed != null -> line("stato", "fallita: ${job.failed}")
        else -> {
          line("stato", job.stage)
          line("avanzamento", "${(job.progress * 100).toInt()}%")
        }
      }
      line("lavori in corso nel motore", ctx.engine.jobsInFlight)
    }
  }
}

class CancelConversionTool : AiTool<ConvertToolContext> {
  override val name = "annulla_conversione"
  override val group: AiToolGroup = ConvertToolGroup.CONVERT
  override val description = "Annulla le conversioni in corso."
  override val parameters = Schema.obj(emptyMap())
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: ConvertToolContext): ToolOutput {
    if (ctx.engine.jobsInFlight == 0) return ToolOutput("non c'e' nessuna conversione in corso")
    ctx.engine.cancelAll()
    return ToolOutput("fatto: conversioni annullate")
  }
}

class OpenConverterTool : AiTool<ConvertToolContext> {
  override val name = "apri_convertitore"
  override val group: AiToolGroup = ConvertToolGroup.CONVERT
  override val description = "Apre Convert to it!, se serve gia' con un file da convertire e il formato di arrivo scelto."
  override val parameters = Schema.obj(mapOf("file" to Schema.str("l'indirizzo content:// del file (facoltativo)"), "a" to Schema.str("il formato di arrivo da preselezionare (facoltativo)")))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: ConvertToolContext): ToolOutput {
    val fileArg = args.str("file")
    val intent = if (fileArg != null && (fileArg.startsWith("content://") || fileArg.startsWith("file://"))) {
      Intent(Intent.ACTION_SEND).apply {
        setClassName(ctx.context.packageName, MainActivity::class.java.name)
        type = "*/*"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(fileArg))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    } else {
      ctx.context.packageManager.getLaunchIntentForPackage(ctx.context.packageName)
    } ?: return ToolOutput.error("non riesco ad aprire l'app")
    args.str("a")?.let { intent.putExtra(EXTRA_TARGET_FORMAT, it) }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { ctx.context.startActivity(intent); ToolOutput("fatto: Convert to it! aperta") }
      .getOrElse { ToolOutput.error("non riesco ad aprire l'app: ${it.message}") }
  }

  companion object {
    /** Il formato che l'app preseleziona quando un file arriva da fuori. */
    const val EXTRA_TARGET_FORMAT = "com.p2r3.convert.extra.TARGET_FORMAT"
  }
}

class EngineStateTool : AiTool<ConvertToolContext> {
  override val name = "motore_stato"
  override val group: AiToolGroup = ConvertToolGroup.CONVERT
  override val description = "Lo stato del motore di conversione: se e' acceso, quanti formati conosce, quanti pezzi pesanti sono gia' scaricati."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ConvertToolContext): ToolOutput {
    val store = ctx.engine.assetStore
    val downloaded = store.downloadedBytes()
    val total = store.totalRemoteBytes()
    return ToolText.output {
      line(
        "motore",
        when (ctx.engine.status.value) {
          EngineStatus.Cold -> "spento (si accende alla prima conversione)"
          EngineStatus.Booting -> "in accensione"
          EngineStatus.Ready -> "pronto"
          EngineStatus.Broken -> "guasto: apri l'app per farlo ripartire"
        },
      )
      line("formati conosciuti", ctx.engine.formats.value.size)
      if (total > 0) line("pezzi scaricati", "${formatSize(downloaded)} di ${formatSize(total)}")
      store.failure.value?.let { line("ultimo errore", it) }
      line("conversioni in corso", ctx.engine.jobsInFlight)
    }
  }
}

class DownloadPackTool : AiTool<ConvertToolContext> {
  override val name = "scarica_pacchetto"
  override val group: AiToolGroup = ConvertToolGroup.CONVERT
  override val description = "Scarica i pezzi pesanti del motore per usarlo senza rete. Chiede conferma: sono decine di megabyte."
  override val parameters = Schema.obj(emptyMap())
  override val needsConfirmation = true
  override val longRunning = true
  override val isAction = true

  override suspend fun describe(args: JsonObject, ctx: ConvertToolContext): ConfirmationText {
    val store = ctx.engine.assetStore
    val missing = store.totalRemoteBytes() - store.downloadedBytes()
    return ConfirmationText("Scaricare i pezzi del motore?", "Circa ${formatSize(missing)} da scaricare, poi il convertitore funziona anche senza rete.")
  }

  override suspend fun run(args: JsonObject, ctx: ConvertToolContext): ToolOutput {
    val store = ctx.engine.assetStore
    val missing = store.totalRemoteBytes() - store.downloadedBytes()
    if (missing <= 0) return ToolOutput("i pezzi del motore sono gia' tutti scaricati")
    ctx.startService()
    val result = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.downloadAll() } }
    return if (result.isSuccess) {
      ToolOutput("fatto: scaricati ${formatSize(store.downloadedBytes())} di ${formatSize(store.totalRemoteBytes())}")
    } else {
      ToolOutput.error("scaricamento non riuscito: ${store.failure.value ?: result.exceptionOrNull()?.message ?: "errore di rete"}")
    }
  }
}

class ShareConvertedTool : AiTool<ConvertToolContext> {
  override val name = "condividi_convertito"
  override val group: AiToolGroup = ConvertToolGroup.CONVERT
  override val description = "Apre il menu di condivisione con l'ultimo file convertito."
  override val parameters = Schema.obj(emptyMap())
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: ConvertToolContext): ToolOutput {
    val files = LastConversion.files
    if (files.isEmpty()) return ToolOutput("non c'e' nessun file convertito di recente da condividere")
    val intent = ctx.app.fileGateway.shareIntent(files) ?: return ToolOutput.error("i file convertiti non ci sono piu' nella cache")
    val chooser = Intent.createChooser(intent, "Condividi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { ctx.context.startActivity(chooser); ToolOutput("fatto: menu di condivisione aperto per ${files.joinToString(", ") { it.name }}") }
      .getOrElse { ToolOutput.error("non riesco ad aprire la condivisione") }
  }
}

object ConvertTools {
  fun registry(): ToolRegistry<ConvertToolContext> = ToolRegistry(
    tools = listOf(
      FormatSearchTool(), PossibleConversionsTool(), ConvertFileTool(), ConversionStatusTool(), CancelConversionTool(),
      OpenConverterTool(), EngineStateTool(), DownloadPackTool(), ShareConvertedTool(),
    ),
    groups = ConvertToolGroup.entries.toList(),
    actionGroup = null,
  )
}
