# Convert to it! — Android

Convertitore universale per Android. L'interfaccia è nativa (Kotlin, Jetpack
Compose, Material 3 Expressive); il motore di conversione è
[p2r3/convert](https://github.com/p2r3/convert), usato **senza modifiche** e
pilotato in modalità headless.

## Crediti e licenza

Le conversioni sono opera di **"Convert to it!" di PortalRunner (p2r3) e dei
suoi collaboratori** — <https://github.com/p2r3/convert>. Questo repository non
contiene una riga del loro codice modificata: il motore è un submodule git
pinnato a un commit upstream, e le nostre aggiunte (`engine/bridge/`) sono file
nuovi copiati accanto ai loro in fase di build.

Il motore è distribuito sotto **GNU GPL v2**. Poiché l'app lo incorpora e lo
ridistribuisce dentro l'APK, l'intera applicazione è a sua volta GPL-2.0: vedi
[LICENSE](LICENSE). I sorgenti completi, inclusi quelli necessari a ricostruire
l'APK, sono in questo repository.

## Com'è fatto

```
app/                     app Android nativa
convert/                 submodule: motore upstream, intoccato
engine/bridge/           le nostre aggiunte al motore (copiate in convert/ al build)
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
