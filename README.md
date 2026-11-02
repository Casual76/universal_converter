# Convert to it! — Android

Convertitore universale per Android. L'interfaccia è nativa (Kotlin, Jetpack
Compose) e poggia sul [Fluid Engine](https://github.com/Casual76/fluid-engine)
3.0, le fondamenta condivise delle app Pampa: pagine, vetro, tasti, menu e
impostazioni sono i suoi. Il motore di conversione è
[p2r3/convert](https://github.com/p2r3/convert), usato **senza modifiche** e
pilotato in modalità headless.

## Due programmi, due licenze

L'APK contiene **due programmi distinti**, ciascuno con la sua licenza e i suoi
sorgenti, che si parlano solo scambiandosi messaggi.

| | L'app | Il motore di conversione |
|---|---|---|
| Cos'è | l'interfaccia nativa: schermate, file, impostazioni, il ponte verso l'assistente Aria, il servizio che lavora ad app chiusa | "Convert to it!" di PortalRunner (p2r3) e collaboratori, più il nostro ponte headless che lo fa girare senza la sua interfaccia web |
| Linguaggio | Kotlin | TypeScript/JavaScript e WebAssembly, in una WebView invisibile |
| Licenza | **GNU GPL v3** — [LICENSE](LICENSE) | **GNU GPL v2** — [convert/LICENSE](https://github.com/p2r3/convert/blob/master/LICENSE) e [engine/LICENSE](engine/LICENSE) |
| Sorgenti | `app/`, i file Gradle alla radice, `fluid-engine/` (submodule) | `convert/` (submodule, intoccato), `engine/` (ponte, cache dei formati, script di build), `docs/legacy-webui-hacks.patch` |

**Perché due.** Il Fluid Engine dalla 3.0 contiene codice GPL-3.0 (i pulsanti e
le pagine vengono da [LibrePods](https://github.com/librepods-org/librepods)),
quindi un'app che lo usa è GPL-3.0. Il motore di conversione è GPL-2.0 senza «o
versioni successive», e le due licenze non si possono combinare in un programma
solo. Per questo restano due programmi: l'app non contiene codice del motore e
il motore non contiene codice dell'app.

**Come si parlano.** L'app carica il motore in una WebView e da lì in poi
scambia solo messaggi, in un protocollo documentato da entrambi i lati
(`engine/bridge/engine.ts` per il motore, `app/.../engine/EngineModels.kt` e
`ConversionEngine.kt` per l'app):

- l'app chiama `window.ConvertEngine.boot()`, `convert(richiesta JSON)` e
  `cancel(job)`;
- il motore risponde con eventi JSON su `AndroidEngine.emit(...)` (pronto,
  percorso, passo, fatto, errore, log) e restituisce i file convertiti come
  `ArrayBuffer` su un `MessagePort` (o a pezzi in base64 su
  `AndroidEngine.chunk(...)` dove la WebView non lo permette);
- i file del motore e quelli da convertire gli arrivano per URL, da un'origine
  locale servita dall'app (`appassets.androidplatform.net/convert/` e `/io/`).

Nessun file Kotlin importa, copia o traduce codice del motore, e nessun file del
motore viene dall'app: la sola cosa in comune è il formato dei messaggi.

**Cosa c'è nell'APK.** L'app compilata; il motore compilato in
`assets/convert/` (il bundle JavaScript, i WASM leggeri e `cache.json`); i
motori pesanti (FFmpeg, Pandoc, Typst, ImageMagick) **non** ci sono: si scaricano
alla prima conversione che li chiede, dalla release `engine-<commit>` di questo
repository, e si verificano con lo SHA-256.

**Dove stanno i sorgenti.** Tutti e due in questo repository, al tag della
release, con i submodule: `git clone --recursive`. Il motore upstream è anche su
<https://github.com/p2r3/convert>. I motori WebAssembly che il motore porta con
sé (FFmpeg, Pandoc, ImageMagick, Typst e gli altri delle sue dipendenze npm)
hanno ciascuno la propria licenza, dichiarata nei loro pacchetti: non sono stati
verificati uno per uno.

La pagina **Impostazioni › Due programmi, due licenze** dell'app dice le stesse
cose, con i collegamenti ai sorgenti e ai testi delle due licenze, e sotto ci
sono i crediti delle opere di terze parti che il Fluid Engine porta nell'APK.

## Crediti

Le conversioni sono opera di **"Convert to it!" di PortalRunner (p2r3) e dei
suoi collaboratori** — <https://github.com/p2r3/convert>. Questo repository non
contiene una riga del loro codice modificata: il motore è un submodule git
pinnato a un commit upstream, e le nostre aggiunte (`engine/bridge/`) sono file
nuovi copiati accanto ai loro in fase di build.

## Com'è fatto

```
app/                     l'app Android nativa (GPL-3.0)
fluid-engine/            submodule: il Fluid Engine, agganciato a un tag
convert/                 submodule: motore upstream, intoccato (GPL-2.0)
engine/bridge/           le nostre aggiunte al motore (copiate in convert/ al build)
engine/engine.gradle.kts i task che costruiscono il motore (applicati da app/)
engine/cache.json        elenco formati precalcolato, committato
```

Il motore gira dentro una WebView completamente trasparente che l'utente non
vede mai. Non c'è più traccia della UI web di upstream: l'app parla con il
motore attraverso `window.ConvertEngine`, definito in `engine/bridge/engine.ts`.

Tre scelte spiegano quasi tutto il comportamento dell'app:

**Il submodule non si tocca mai.** Le nostre aggiunte sono file nuovi, copiati
dentro `convert/` dal task `engineSyncBridge` prima di ogni build. Aggiornare il
motore è quindi un `git pull` dentro il submodule, senza un solo conflitto.

**I formati sono precalcolati.** Ogni handler dichiara i formati supportati solo
dopo `init()`, e per FFmpeg, Pandoc, Typst e ImageMagick questo significa
caricare decine di MB di WASM. `engine/cache.json` viene generato una volta in
fase di build, così l'app apre l'elenco dei formati senza avviare un solo
motore.

**I motori pesanti si scaricano a richiesta.** Quattro blob WASM valgono ~130 MB
su 166 MB di bundle. Restano fuori dall'APK e vengono scaricati la prima volta
che servono, poi restano sul telefono. In impostazioni c'è "Scarica tutto" per
l'uso completamente offline.

## Build

Servono JDK 17, Android SDK e Node.

```bash
git clone --recursive <repo> && cd universal_converter
./gradlew :app:assembleDebug
```

Se il repo è già clonato senza submodule:

```bash
git submodule update --init --recursive
```

Il Fluid Engine si aggiorna con il suo script, dalla radice dell'app:

```powershell
powershell -ExecutionPolicy Bypass -File fluid-engine\tools\engine-update.ps1 -AppRoot . -Version 3.0.0-rc.1
```

## Verificare il bridge senza telefono

```bash
cd convert && node src/android/smoke-test.mjs json yaml
```

Avvia il motore esattamente come fa l'app, gli passa un file via HTTP come fa la
shell e rilegge il risultato: se questo passa, il problema di una conversione
che non funziona è nell'app, non nel bridge. I nomi dei formati sono quelli
interni del motore (minuscoli: `json`, `yaml`, `png`, `bson`…).

## Aggiornare il motore

```bash
git -C convert pull origin master
./gradlew :app:engineFormatCache -PrefreshFormatCache
```

Il secondo comando riavvia ogni handler per rigenerare `engine/cache.json`
(richiede Chrome via puppeteer; `CHROME_PATH` permette di riusarne uno già
installato). Va rifatto a ogni aggiornamento del motore, altrimenti i formati
nuovi non compaiono nell'app. Poi committa `engine/cache.json`.

## Pubblicare il pacchetto motori

Ogni versione del motore ha il suo pacchetto di file on-demand, che deve essere
raggiungibile all'URL configurato in `ENGINE_ASSET_BASE_URL`:

```bash
./gradlew :app:engineAssetPack
```

Produce `app/build/outputs/engine/engine-pack-<commit>.zip` e i singoli file in
`app/build/generated/engine/pack/`. I file vanno caricati **singoli** (non lo
zip) su una release taggata `engine-<commit>`, perché l'app scarica un file per
volta:

```bash
gh release create engine-<commit> app/build/generated/engine/pack/* \
  --title "Engine <commit>" --notes "Motori on-demand"
```

L'app verifica ogni download con lo SHA-256 registrato in
`engine-assets.json`, quindi un file caricato male viene rifiutato invece di
rompere una conversione.

La soglia oltre la quale un file diventa on-demand si cambia con
`-Pengine.bundleThresholdBytes=<n>`; l'URL base con
`-Pengine.assetBaseUrl=<url>`.
