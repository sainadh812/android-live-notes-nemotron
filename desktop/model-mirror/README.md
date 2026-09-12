# Verified speech model mirror

`manifest.json` pins the same six GGUF files as the Android/Desktop
`SpeechModel` catalog: 2,953,828,320 bytes in total. Models are hosted as release
assets, outside Git history. This mirror preserves the publisher's exact bytes;
it does not produce new conversions or change inference behavior.

Run on a machine with access to the original public Hugging Face sources:

```sh
python desktop/model-mirror/prepare.py --output /tmp/live-notes-model-mirror
```

An optional `--cache DIRECTORY` reuses local weights only after their exact size
and SHA-256 match the manifest. All six immutable original/conversion model
cards must be retrieved and declare their expected licenses before preparation
can succeed. The weights are streamed through bounded buffers to temporary
files, verified, then renamed into place. Interrupted or incorrect downloads
cannot become final assets. No Hugging Face token or cloud inference is used.

Preparation creates nine root assets:

- Six verified `.gguf` files, retaining their original names.
- `manifest.json`, copied byte-for-byte from the reviewed manifest.
- `model-attribution.zip`, containing the required Notice, license text, complete
  pinned model cards, their source URLs and calculated document hashes.
- `SHA256SUMS`, covering the other eight assets.

ZIP entries use a fixed timestamp and permissions and are written in sorted
order. Identical pinned inputs therefore produce identical release asset bytes
on retries. Publish all nine files together and retain the attribution archive
with redistributed model copies. The Windows application also needs to provide
the relevant Notice and license files to users who install a model from the app.

The primary license sources and applicable redistribution requirements are
documented in [licenses/SOURCES.md](licenses/SOURCES.md). Moonshine Tiny uses
MIT, Nemotron English uses the NVIDIA Open Model License, and the four Nemotron
3.5 variants use OpenMDW 1.1. Speaker analysis models are a separate optional
download already served by immutable GitHub release assets in
`desktop/speaker-worker/models.json`.

The preparation script never creates a GitHub release or uploads files.

Run its network-free regression checks:

```sh
python -m unittest discover -s desktop/model-mirror/tests -v
```
