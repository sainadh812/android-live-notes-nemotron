# Real Gemma prototype results — 2026-10-02

**Short end-to-end summarization worked with the real model and the shared Android
Java pipeline. Full two-hour summarization quality is still unvalidated.** These
are Linux CPU tests, not S25 Ultra performance measurements.

| Test | Observed result |
| --- | --- |
| Short shared Java pipeline, thinking off and on | Both completed; all four selected facts retained with correct citations. |
| 125-minute synthetic transcript, cached CPU, 4,096 context | Two of 116 extraction chunks completed and validated. Stopped to bound the test; no final long summary. |
| Same long fixture, uncached CPU, 8,192 context | Killed before first answer; exit 137 / SIGKILL. Suspected memory exhaustion; kernel OOM was not confirmed. |
| Short direct inference smoke, 2,048 context | Both thinking modes returned correct prose. This separate smoke did not exercise the JSON pipeline. |

## Short end-to-end evidence

The 43-word synthetic source contains a provisional date/budget, a later
correction, an action, and an unknown owner. Both final summaries correctly state:

- November 19 replaces November 12.
- $95,000 replaces $120,000.
- Priya will update the risk register by October 9.
- Support ownership remains unassigned.

Each statement cites the matching source sentence. The non-thinking summary also
retains an explicitly provisional historical-proposal bullet; thinking removes
that redundant bullet. This single small example does not establish a general
accuracy advantage from reasoning. Four selected presence/citation checks passed
in each mode; this is **not an overall accuracy score**. Manual review also
confirmed the corrections, action owner/deadline, and absent support owner.

The shared pipeline performed one real extraction, a final summary without
thinking, then reused the extraction checkpoint for a final summary with
thinking. No response was mocked or substituted. No format retry was needed.

| Real inference call | Elapsed | Native generated tokens | Native decode rate |
| --- | ---: | ---: | ---: |
| Extraction, no thinking | 73.64 s | 301 | 5.37 tokens/s |
| Final, no thinking | 66.00 s | 301 | 5.70 tokens/s |
| Final, thinking budget 256 | 103.28 s | 507, including thought tokens | 5.60 tokens/s |

The complete comparison took 265.13 seconds including verification/setup; peak
process RSS was 4.38 GiB. This was one fixed-order run on a four-core Linux host.
Warm state and order were not controlled, so these are observations, not a
rigorous latency comparison. See the full visible answers and source spans in
[the short pipeline report](reports/short-pipeline-cached-4096.json).

## Long test boundary

The generated fixture has 19,296 words and 125 timestamped turns spanning a
nominal 125 minutes. It is authored and templated, not real meeting audio or a
representative quality dataset. It includes corrections late in the discussion,
unassigned owners, disagreement, and quoted instructions that should be ignored.

The cached 4,096-context configuration planned 116 extraction chunks using the
same conservative UTF-8 byte budget as Android. Two completed calls took 147.04
and 167.79 seconds, with 620 and 654 generated tokens. The actual Java pipeline
accepted their JSON and source IDs. A third call was interrupted before its
answer after the two checkpoints were saved. At those two observed call times,
extraction alone would take roughly five hours on this host, before merging;
that extrapolation is not a phone runtime prediction.

Observed peak RSS was 4.52 GiB; the native compiled-weight cache occupied
2,210,334,416 bytes (2.06 GiB). The earlier uncached 8,192-context attempt was
observed at 12.17 GiB RSS before SIGKILL. Context size and caching both changed,
so this is not an isolated test of the cache's memory effect. Failure and partial
evidence are retained in [the uncached failure report](reports/long-8192-killed.json)
and [the cached partial report](reports/long-4096-partial.json).

No final long summary was produced. Long-range corrections, completeness,
multi-level merge quality, and quoted-instruction resistance are **not validated
by the two successful initial chunks**.

## Reproducibility and limits

- Model: real Gemma 4 E4B LiteRT artifact, 3,659,530,240 bytes; SHA-256
  `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`.
- Runtime: `litert-lm-api==0.17.1`, Linux x86_64 CPU, four threads; speculative
  decoding disabled; top-k 1, top-p 1, temperature 0, seed 0.
- Shared pipeline prompt version: `local-evidence-v2`. Cached tests use context
  4,096, total output cap 1,024, template reserve 256. Thinking is enabled only
  for the compared final call and stays inside the total output cap.
- Source budgeting uses a conservative UTF-8 byte bound, not measured tokens.
  Native tokenizer and runtime counts are logged separately.
- The earlier direct smoke used context 2,048, output 256 and thought budget 64:
  24.64 s without thinking, 27.02 s with it, peak 10.00 GiB RSS without disk cache.
  Its [report](reports/short-direct-smoke.json) is separate from pipeline evidence.
- An initial direct smoke returned a correct model answer but the harness failed
  while reading the runtime's dictionary response. The parser was corrected and
  the clean smoke and shared-pipeline runs above were rerun successfully. Earlier
  coordinator-interrupted runs are not counted as successes.

The six fast harness tests verify fixture, parser, budget, and evaluation code;
they do not run inference. Reproduction commands are in [README.md](README.md).
An S25 Ultra test is still needed for Android GPU compatibility, memory,
temperature, battery, speed, and a complete real two-hour transcript. These
results support trying the prototype, not claiming production readiness.
