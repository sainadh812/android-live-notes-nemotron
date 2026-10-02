# Gemma prototype evaluation

This harness runs real Gemma 4 E4B inference through `litert-lm-api==0.17.1` on a
Linux CPU. Its default pipeline is the **same Java `SummaryPipeline` used by the
Android prototype**, invoked through `JavaBenchmarkBridge`. It is not a phone
performance benchmark. No transcript is sent to a service.

See [RESULTS.md](RESULTS.md) for the completed short tests and the explicitly
incomplete 125-minute test. The full test has not established long-meeting quality.

The generated 125-minute fixture contains 19,296 words across 125 timestamped
turns. It is an authored, templated **synthetic stress test**, not recorded human
speech or a representative meeting-quality benchmark. It includes early
proposals, later corrections, unassigned owners, disagreement, and an instruction
embedded in quoted source material. Selected-fact checks are deliberately
reported as presence/citation checks, not an overall accuracy percentage. Human
review must assess whether cited passages support the statements and whether
important information was omitted.

## Run

Use Python 3.10+, Java 17, and Gson in the Gradle cache (build the Android
prototype first). Install only the inference API; the large conversion toolchain
is not required:

```sh
python3 -m venv /tmp/gemma-eval-venv
/tmp/gemma-eval-venv/bin/pip install -r scripts/gemma-prototype/requirements.txt
python3 scripts/gemma-prototype/make_fixture.py
python3 -m unittest discover -s scripts/gemma-prototype -p 'test_*.py' -v
```

Download `gemma-4-E4B-it.litertlm` from the
[pinned LiteRT model revision](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm).
The harness requires exactly **3,659,530,240 bytes**, with SHA-256
`0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`.
It rejects a different or incomplete model. Weights are not committed.

The tested CPU configuration uses a persistent compiled-weight cache, 4,096
context tokens, and a 1,024-token total output cap. Allow at least 8 GB free for
the model, cache, and reports. This full-fixture command can take hours on a CPU;
start with the short fixture below. The monitor retains process/memory evidence
and stops if less than 1 GiB of disk space remains.

```sh
/tmp/gemma-eval-venv/bin/python scripts/gemma-prototype/monitor_run.py \
  --report /tmp/gemma-eval-monitor.json --timeout-seconds 21600 -- \
  /tmp/gemma-eval-venv/bin/python scripts/gemma-prototype/benchmark.py \
  --model /path/to/gemma-4-E4B-it.litertlm \
  --transcript scripts/gemma-prototype/fixtures/synthetic-125-minute-meeting.txt \
  --expected scripts/gemma-prototype/fixtures/synthetic-125-minute-meeting.expected.json \
  --output /tmp/gemma-eval-result \
  --context 4096 --output-tokens 1024 --thinking-tokens 256 \
  --runtime-cache /tmp/gemma-eval-native-cache \
  --budget android-bytes --compare-thinking
```

For the short end-to-end test, replace the two `synthetic-125-minute-meeting`
fixture filenames with `synthetic-short-meeting` and use a different output
directory. It tests corrected dates, revised budgets, an assigned action, and
an owner who remains unassigned through the actual shared Java pipeline.

The default conservative UTF-8 byte budget mirrors Android. Exact tokenizer
counts are also logged using the actual loaded model. Each call reserves room
for the template and total generation; the configured generation cap includes
thought and answer tokens. Extraction and merging run without thinking. The
comparison changes only thinking during final synthesis; extraction/merge
checkpoints are shared. The 256-token thought allowance does not increase the
total generation cap. Native compiled-weight disk caching is enabled in the
command above; speculative decoding is disabled. The earlier 8,192-context,
2,048-output uncached attempt exited with SIGKILL before its first answer.
That configuration is not the recommended CPU test command.

Results include model/runtime identity, all real generation inputs and visible
responses, source IDs, failures, elapsed time, process peak RSS, final summaries,
and selected-fact checks. Thinking content is not saved. Complete validated
responses are resumable. A malformed or truncated response is reported as a
failure; the harness does not repair it by substituting an invented answer.
Source-ID validity alone does not establish semantic correctness.

`--python-pipeline --budget exact-tokenizer` enables a separate experimental
tokenizer-budget pipeline. Results from that mode do not demonstrate Android
pipeline parity and are labelled accordingly by their configuration.

The fast Python unit tests validate budgeting, fixture metadata, parsers, and
evaluation checks. They do **not** run a model. Only a completed benchmark report
with the verified real model is inference evidence. Phone speed, battery,
temperature, Android GPU compatibility, and production transcription quality
still require tests on the S25 Ultra.
