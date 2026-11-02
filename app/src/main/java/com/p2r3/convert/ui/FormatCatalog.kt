package com.p2r3.convert.ui

import com.p2r3.convert.engine.FormatOption
import dev.antigravity.fluidengine.ui.theme.FluidTone

/** Which side of the conversion the picker is choosing. */
enum class PickerTarget { Source, Target }

/**
 * Il catalogo dei formati come lo vede chi sceglie: quali si offrono, in che famiglie, in che ordine.
 *
 * Tutto puro (nessun Compose), perche' sono le regole che decidono cosa una persona trova cercando
 * fra novecento voci, e si provano su un computer.
 */
object FormatCatalog {

    /**
     * Le famiglie nell'ordine in cui le cerca chi converte un file, non in ordine alfabetico: le
     * immagini e i documenti prima dei modelli 3D. Quelle che non sono qui vanno in fondo, per numero.
     */
    private val GroupOrder = listOf(
        "image", "document", "audio", "video", "archive", "text", "spreadsheet",
        "presentation", "data", "code", "font", "model", "database", "application",
    )

    private val GroupLabels = mapOf(
        "image" to "Immagini",
        "document" to "Documenti",
        "audio" to "Audio",
        "video" to "Video",
        "archive" to "Archivi",
        "text" to "Testo",
        "spreadsheet" to "Fogli di calcolo",
        "presentation" to "Presentazioni",
        "data" to "Dati",
        "code" to "Codice",
        "font" to "Font",
        "model" to "Modelli 3D",
        "database" to "Database",
        "application" to "Applicazioni",
    )

    /**
     * I formati che si possono scegliere da questo lato.
     *
     * In modalita' semplice lo stesso formato offerto da piu' strumenti e' una riga sola (la coppia
     * MIME e nome breve, la stessa con cui il motore riconosce un formato "uguale"): sceglie lo
     * strumento il motore. In modalita' avanzata ogni strumento ha la sua riga.
     */
    fun available(formats: List<FormatOption>, direction: PickerTarget, simpleMode: Boolean): List<FormatOption> {
        val filtered = formats.filter { if (direction == PickerTarget.Source) it.from else it.to }
        return if (simpleMode) filtered.distinctBy { it.mime to it.format } else filtered
    }

    /** Le famiglie presenti, nell'ordine di [GroupOrder]; le sconosciute dopo, le piu' numerose prima. */
    fun groups(available: List<FormatOption>): List<String> {
        val counts = available.groupingBy { it.group }.eachCount()
        return counts.keys.sortedWith(
            compareBy<String> { GroupOrder.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                .thenByDescending { counts[it] ?: 0 }
                .thenBy { it },
        )
    }

    /**
     * Le righe da mostrare per una ricerca e una famiglia.
     *
     * Ogni parola della ricerca deve comparire (nome, estensione, MIME o strumento): "jpeg 2000" trova
     * JPEG 2000 e non tutti i JPEG. In cima i formati il cui nome breve o l'estensione e' proprio
     * quello che si e' scritto, perche' chi scrive "jpg" vuole JPEG e non "raw MJPEG video".
     */
    fun visible(available: List<FormatOption>, query: String, group: String?): List<FormatOption> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val needle = words.joinToString(" ")
        return available.asSequence()
            .filter { group == null || it.group == group }
            .filter { option -> words.all { option.searchIndex.contains(it) } }
            .sortedWith(
                compareBy<FormatOption> { option ->
                    when {
                        needle.isEmpty() -> 0
                        option.format.equals(needle, ignoreCase = true) ||
                            option.extension.equals(needle, ignoreCase = true) -> 0
                        option.format.lowercase().startsWith(needle) ||
                            option.extension.lowercase().startsWith(needle) -> 1
                        else -> 2
                    }
                }.thenBy { it.format.lowercase() }.thenBy { it.handler },
            )
            .toList()
    }

    /** Il nome di una famiglia, in italiano; una famiglia che non conosciamo con la maiuscola. */
    fun groupLabel(group: String): String =
        GroupLabels[group] ?: group.replaceFirstChar { it.uppercase() }

    /**
     * Il colore della piastrella di una famiglia. Sta sulla piastrella e mai sulla riga, ed e' quello
     * che fa trovare a colpo d'occhio un'immagine in mezzo a duecento video.
     */
    fun groupTone(group: String): FluidTone = when (group) {
        "image" -> FluidTone.Primary
        "audio" -> FluidTone.Success
        "video" -> FluidTone.Info
        "document", "text", "spreadsheet", "presentation" -> FluidTone.Warning
        "archive" -> FluidTone.Danger
        else -> FluidTone.Neutral
    }

    /** Quello che sta scritto sulla piastrella: il nome breve, al massimo quattro lettere. */
    fun badge(option: FormatOption): String = option.format.uppercase().take(4)

    /**
     * Il formato di partenza di un file: prima l'estensione, e fra quelli con la stessa estensione
     * quello che dichiara lo stesso MIME; senza estensione nota, il MIME.
     */
    fun detect(formats: List<FormatOption>, extension: String, mimeType: String?): FormatOption? {
        val options = formats.filter { it.from }
        if (options.isEmpty()) return null
        val byExtension = options.filter { it.extension.equals(extension, ignoreCase = true) }
        if (byExtension.isNotEmpty()) {
            return byExtension.firstOrNull { it.mime.equals(mimeType, ignoreCase = true) } ?: byExtension.first()
        }
        return options.firstOrNull { it.mime.equals(mimeType, ignoreCase = true) }
    }
}
