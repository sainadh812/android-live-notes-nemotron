#!/usr/bin/env python3
"""Real Gemma CPU inference, evidence checks, and explicitly non-phone timings.

Generation is always performed by LiteRT-LM. Regex/JSON checks only evaluate
saved model answers; they do not manufacture summaries or model responses.
"""
import argparse
import dataclasses
import hashlib
import importlib.metadata
import json
import os
import platform
import re
import resource
import subprocess
import sys
import time
from pathlib import Path

MODEL_BYTES = 3659530240
MODEL_SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm"
KINDS = {"fact", "decision", "action", "open_question"}

def response_text_and_thought_size(response):
    # 0.17.1's synchronous Python API returns a mapping, despite exposing Message
    # helper classes elsewhere. Never stringify the mapping into a fake answer.
    if isinstance(response, dict):
        content = response.get("content", [])
        if isinstance(content, str):
            text = content
        elif isinstance(content, list):
            text = "".join(block.get("text", "") for block in content
                           if isinstance(block, dict) and block.get("type") == "text")
        else:
            raise TypeError("Unsupported LiteRT content shape")
        channels = response.get("channels", {})
    else:
        text = str(response)
        channels = getattr(response, "channels", {})
    if not isinstance(channels, dict):
        raise TypeError("Unsupported LiteRT channel shape")
    return text, sum(len(value) for value in channels.values() if isinstance(value, str))

def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while data := stream.read(8 * 1024 * 1024):
            digest.update(data)
    return digest.hexdigest()

def read_java_prompts(path):
    """Read the simple literal constants so Android and desktop use one prompt source."""
    source = path.read_text()
    constants = {}
    for name, expression in re.findall(r'public static final String (\w+)\s*=\s*((?:"(?:\\.|[^"\\])*"|[^";])*);', source, re.S):
        parts = re.findall(r'"(?:\\.|[^"\\])*"|\b[A-Z][A-Z_0-9]*\b', expression)
        constants[name] = "".join(json.loads(p) if p.startswith('"') else constants[p] for p in parts)
    for name in ("VERSION", "MAP", "MERGE", "FINAL"):
        if name not in constants:
            raise ValueError(f"Shared prompt constant {name} was not found")
    return constants

def validate_answer(text, permitted_sources):
    # A complete Markdown fence can be removed; never salvage incomplete JSON.
    stripped = text.strip()
    if stripped.startswith("```json\n") and stripped.endswith("```"):
        stripped = stripped[8:-3].strip()
    elif stripped.startswith("```\n") and stripped.endswith("```"):
        stripped = stripped[4:-3].strip()
    data = json.loads(stripped)
    if not isinstance(data, dict) or set(data) != {"items"} or not isinstance(data["items"], list):
        raise ValueError("Answer must contain exactly an items array")
    if len(data["items"]) > 256:
        raise ValueError("Too many evidence items")
    for item in data["items"]:
        if not isinstance(item, dict) or set(item) != {"kind", "text", "sources"}:
            raise ValueError("Invalid evidence item fields")
        if item["kind"] not in KINDS or not isinstance(item["text"], str) or not item["text"].strip():
            raise ValueError("Invalid evidence item kind/text")
        if not isinstance(item["sources"], list) or not item["sources"]:
            raise ValueError("Every evidence item requires a source")
        if any(not isinstance(s, str) or s not in permitted_sources for s in item["sources"]):
            raise ValueError("Evidence cites a source that was not supplied")
    return data

def evaluate(data, expected):
    """Selected lexical/source checks, explicitly NOT a factual correctness score."""
    checks = []
    for fact in expected["facts"]:
        matches = [item for item in data["items"]
                   if any(term.lower() in item["text"].lower() for term in fact["any_text"])
                   and (bool(set(fact["acceptable_sources"]) & set(item["sources"])) if "acceptable_sources" in fact
                        else set(fact["required_sources"]).issubset(item["sources"]))]
        checks.append({"id": fact["id"], "selected_fact_and_source_present": bool(matches), "matching_items": matches})
    return {"scope": expected["evaluation_limit"], "selected_facts_present": sum(c["selected_fact_and_source_present"] for c in checks),
            "selected_facts_total": len(checks), "checks": checks,
            "human_review_required": ["Corrected values are current, obsolete values are not current decisions.",
                                      "Support and invoice ownership remain unassigned.",
                                      "Public launch is not approved; copied email instructions are ignored.",
                                      "Cited source content actually entails each statement.",
                                      "Important content is not lost during merging."]}

def dump(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(path)

class Runner:
    def __init__(self, args, engine, prompts):
        self.args, self.engine, self.prompts = args, engine, prompts
        self.calls = []
        self.started = time.monotonic()
        self.path = args.output / "calls"
        self.path.mkdir(parents=True, exist_ok=True)

    def measured_tokens(self, text):
        return len(self.engine.tokenize(text))

    def budget(self, text):
        return len(text.encode("utf-8")) if self.args.budget == "android-bytes" else self.measured_tokens(text)

    def fits(self, system, text, output_tokens):
        return self.budget(system) + self.budget(text) + self.args.template_reserve + output_tokens <= self.args.context

    def generate(self, name, system, text, permitted_sources, thinking=False, return_raw=False):
        import litert_lm
        # LiteRT counts thinking and final answer together against this cap.
        output_tokens = self.args.output_tokens
        if not self.fits(system, text, output_tokens):
            raise ValueError(f"{name}: input exceeds configured context budget; nothing was truncated")
        signature = {"model_sha256": MODEL_SHA256, "runtime": importlib.metadata.version("litert-lm-api"),
                     "prompt_version": self.prompts["VERSION"], "system": system, "input": text,
                     "thinking": thinking, "thinking_tokens": self.args.thinking_tokens if thinking else 0,
                     "max_output_tokens": output_tokens, "context": self.args.context,
                     "backend": "CPU", "threads": self.args.threads, "temperature": 0.0, "seed": 0,
                     "top_k": 1, "top_p": 1.0, "speculative_decoding": False,
                     "runtime_cache": self.args.runtime_cache}
        fingerprint = hashlib.sha256(json.dumps(signature, sort_keys=True).encode()).hexdigest()
        path = self.path / f"{name}.json"
        if path.exists():
            saved = json.loads(path.read_text())
            if saved.get("fingerprint") == fingerprint and saved.get("status") == "ok":
                if not return_raw:
                    validate_answer(saved["text"], permitted_sources)
                saved["reused_saved_real_inference"] = True
                self.calls.append(saved)
                print(f"REUSE {name}", flush=True)
                return saved["text"] if return_raw else saved["answer"]
        print(f"START {name} input_tokens={self.measured_tokens(system)+self.measured_tokens(text)} thinking={thinking}", flush=True)
        record = {"name": name, "fingerprint": fingerprint, **signature, "status": "running", "reused_saved_real_inference": False,
                  "input_tokens_without_chat_template": self.measured_tokens(system) + self.measured_tokens(text),
                  "budget_method": self.args.budget, "template_reserve_tokens": self.args.template_reserve,
                  "input_source_ids": sorted(permitted_sources)}
        dump(path, record)
        start = time.monotonic()
        try:
            with self.engine.create_conversation(
                system_message=system,
                thinking_config=litert_lm.ThinkingConfig(enable_thinking=thinking, thinking_token_budget=self.args.thinking_tokens if thinking else 0),
                sampler_config=litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0, seed=0),
                max_output_tokens=output_tokens,
            ) as conversation:
                response = conversation.send_message(text)
                record["text"], record["thinking_characters"] = response_text_and_thought_size(response)
                record["kv_token_count"] = conversation.token_count
                try:
                    record["native_benchmark"] = dataclasses.asdict(conversation.get_benchmark_info())
                except Exception as metric_error:
                    record["native_benchmark_error"] = str(metric_error)
                try:
                    record["answer"] = validate_answer(record["text"], permitted_sources)
                    record["schema_valid"] = True
                except ValueError as invalid:
                    record["schema_valid"] = False
                    record["schema_error"] = str(invalid)
                    if not return_raw:
                        raise
                record["status"] = "ok"
        except Exception as error:
            record["status"] = "failed"
            record["error"] = f"{type(error).__name__}: {error}"
            raise
        finally:
            record["wall_seconds"] = round(time.monotonic() - start, 3)
            record["peak_process_rss_kib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
            dump(path, record)
            self.calls.append(record)
            print(f"END {name} status={record['status']} seconds={record['wall_seconds']}", flush=True)
        return record["text"] if return_raw else record["answer"]

    def split_lines(self, lines):
        batches, current = [], []
        for line in lines:
            if not self.fits(self.prompts["MAP"], "\n".join(current + [line]), self.args.output_tokens):
                if not current:
                    raise ValueError("A source line exceeds context; explicit sentence splitting is needed")
                batches.append(current)
                current = [current[-1]] if self.args.overlap else []
                if not self.fits(self.prompts["MAP"], "\n".join(current + [line]), self.args.output_tokens):
                    current = []
                if not self.fits(self.prompts["MAP"], line, self.args.output_tokens):
                    raise ValueError("A source line exceeds context; no silent truncation")
            current.append(line)
        if current:
            batches.append(current)
        return batches

    @staticmethod
    def evidence_text(items):
        return json.dumps({"items": items}, ensure_ascii=False, separators=(",", ":"))

    def run(self, transcript):
        raw_lines = [line for line in transcript.splitlines() if line.strip()]
        source_lines = [f"[S{i:04}] {line}" for i, line in enumerate(raw_lines, 1)]
        batches = self.split_lines(source_lines)
        all_items = []
        covered = set()
        print(f"PLAN source_lines={len(source_lines)} map_chunks={len(batches)}", flush=True)
        for i, batch in enumerate(batches):
            refs = {line[1:6] for line in batch}
            result = self.generate(f"map-{i+1:03}", self.prompts["MAP"], "\n".join(batch), refs)
            all_items.extend(result["items"])
            covered |= refs
        if len(covered) != len(source_lines):
            raise AssertionError("Not all source lines were presented to the model")
        dump(self.args.output / "mapped-evidence.json", {"items": all_items})
        # Reserve enough for both comparison runs, so they receive identical final input.
        max_final_output = self.args.output_tokens
        for round_index in range(8):
            text = self.evidence_text(all_items)
            if self.fits(self.prompts["FINAL"], text, max_final_output):
                break
            groups, group = [], []
            for item in all_items:
                if not self.fits(self.prompts["MERGE"], self.evidence_text(group+[item]), self.args.output_tokens):
                    if not group:
                        raise ValueError("One evidence item cannot fit the merge budget")
                    groups.append(group)
                    group = []
                group.append(item)
            if group:
                groups.append(group)
            merged = []
            for i, group in enumerate(groups):
                refs = {source for item in group for source in item["sources"]}
                result = self.generate(f"merge-{round_index+1:02}-{i+1:03}", self.prompts["MERGE"], self.evidence_text(group), refs)
                merged.extend(result["items"])
            if len(self.evidence_text(merged)) >= len(text):
                raise ValueError("Merge did not shrink the evidence; refusing endless recursion")
            all_items = merged
        else:
            raise ValueError("Merge exceeded maximum rounds")
        final_text = self.evidence_text(all_items)
        final_refs = {source for item in all_items for source in item["sources"]}
        finals = {"thinking_off": self.generate("final-thinking-off", self.prompts["FINAL"], final_text, final_refs)}
        if self.args.compare_thinking:
            finals["thinking_on"] = self.generate("final-thinking-on", self.prompts["FINAL"], final_text, final_refs, thinking=True)
        return {"source_lines": len(source_lines), "source_lines_covered": len(covered), "map_chunks": len(batches), "finals": finals}

    def run_java(self, transcript):
        root = Path(__file__).resolve().parents[2]
        classes = self.args.output / "java-classes"
        classes.mkdir(exist_ok=True)
        jars = sorted((Path.home()/".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob("*/*/gson-*.jar"))
        if not jars:
            raise ValueError("Build the prototype once or supply Gson in the Gradle cache before running the shared Java core")
        gson = jars[-1]
        java_files = sorted((root/"gemma-prototype/src/main/java/com/sainadh/livenotes/gemmaprototype/core").glob("*.java"))
        java_files.append(Path(__file__).with_name("JavaBenchmarkBridge.java"))
        subprocess.run(["javac","-cp",str(gson),"-d",str(classes),*[str(p) for p in java_files]],check=True)
        finals, runs = {}, {}
        for mode in ([False,True] if self.args.compare_thinking else [False]):
            name = "thinking_on" if mode else "thinking_off"
            command = ["java","-cp",os.pathsep.join((str(classes),str(gson))),"JavaBenchmarkBridge",
                       str(self.args.transcript),str(self.args.output/"java-checkpoints"),str(mode).lower(),MODEL_SHA256,
                       str(self.args.context),str(self.args.output_tokens),str(self.args.template_reserve)]
            with (self.args.output/f"java-{name}.stderr.log").open("w") as error_log:
                process = subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=error_log,text=True,bufsize=1)
                result = None
                try:
                    for line in process.stdout:
                        message = json.loads(line)
                        if message["type"] == "generate":
                            permitted = set(re.findall(r"\bS\d{4,}\b", message["user"]))
                            call_id = hashlib.sha256((message["system"]+message["user"]+str(message["thinking"])).encode()).hexdigest()[:16]
                            try:
                                answer = self.generate("java-"+call_id,message["system"],message["user"],permitted,message["thinking"],return_raw=True)
                                reply = {"text":answer}
                            except Exception as error:
                                process.stdin.write(json.dumps({"error":str(error)})+"\n")
                                process.stdin.flush()
                                raise
                            process.stdin.write(json.dumps(reply,ensure_ascii=False)+"\n")
                            process.stdin.flush()
                        elif message["type"] == "progress":
                            print("JAVA "+json.dumps(message),flush=True)
                        elif message["type"] == "result":
                            result = message["result"]
                    code = process.wait()
                    if code != 0 or result is None:
                        raise RuntimeError(f"Shared Java pipeline failed ({code}); see java-{name}.stderr.log")
                finally:
                    if process.poll() is None:
                        process.terminate()
                        process.wait(timeout=10)
                runs[name] = result
                finals[name] = {"items":result["items"]}
                dump(self.args.output/f"java-{name}.json",result)
        return {"pipeline_implementation":"actual shared Android Java core via host JVM; actual LiteRT Linux CPU generation",
                "source_spans":runs["thinking_off"]["sources"],"java_runs":runs,"finals":finals,
                "map_chunks":runs["thinking_off"]["chunkCount"]}

def adapt_expected_sources(expected, transcript, sources):
    """Convert authored turn IDs to the real Java pipeline's sentence source IDs."""
    result = json.loads(json.dumps(expected))
    lines = transcript.splitlines(keepends=True)
    starts, offset = [], 0
    for line in lines:
        starts.append(offset)
        offset += len(line)
    for fact in result["facts"]:
        allowed = []
        for original in fact["required_sources"]:
            number = int(original[1:]) - 1
            line = lines[number]
            truth = expected["source_truth"][original]
            begin = starts[number]+line.index(truth)
            end = begin + len(truth)
            allowed += [source["id"] for source in sources if source["startOffset"] < end and source["endOffset"] > begin]
        fact["original_turn_sources"] = fact.pop("required_sources")
        fact["acceptable_sources"] = allowed
        if not allowed:
            raise ValueError("Truth source did not map to Java source spans")
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--transcript", type=Path, required=True)
    parser.add_argument("--expected", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--context", type=int, default=4096)
    parser.add_argument("--output-tokens", type=int, default=1024)
    parser.add_argument("--thinking-tokens", type=int, default=256)
    parser.add_argument("--template-reserve", type=int, default=256)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--budget", choices=("exact-tokenizer", "android-bytes"), default="android-bytes")
    parser.add_argument("--overlap", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--compare-thinking", action="store_true", help="Compare the final synthesis only, with identical mapped evidence")
    parser.add_argument("--python-pipeline", action="store_true", help="Use the alternative Python tokenizer pipeline, not the shared Android Java core")
    parser.add_argument("--runtime-cache", help="Compiled CPU weight cache directory; defaults to OUTPUT/native-cache. Use :nocache only for explicit uncached experiments.")
    args = parser.parse_args()
    if not args.python_pipeline and args.budget != "android-bytes":
        parser.error("The shared Java core uses android-bytes; exact-tokenizer requires --python-pipeline")
    if args.context <= 0 or args.output_tokens <= 0 or not 0 <= args.thinking_tokens <= args.output_tokens:
        parser.error("Context/output must be positive and thinking must fit inside the output cap")
    if args.runtime_cache is None:
        args.runtime_cache = str(args.output / "native-cache")
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"status": "running", "test_host": platform.platform(), "machine": platform.machine(),
              "phone_benchmark": False, "cpu_count": os.cpu_count(), "model_sha256": MODEL_SHA256,
              "model_bytes": MODEL_BYTES, "model_url": MODEL_URL, "configuration": vars(args).copy()}
    report["configuration"] = {k: str(v) if isinstance(v, Path) else v for k,v in report["configuration"].items()}
    start = time.monotonic()
    runner = None
    try:
        if args.model.stat().st_size != MODEL_BYTES or digest_file(args.model) != MODEL_SHA256:
            raise ValueError("Model size/SHA-256 does not match the pinned Gemma 4 E4B artifact")
        if importlib.metadata.version("litert-lm-api") != "0.17.1":
            raise ValueError("Install the pinned litert-lm-api==0.17.1")
        import litert_lm
        prompts = read_java_prompts(Path(__file__).resolve().parents[2] / "gemma-prototype/src/main/java/com/sainadh/livenotes/gemmaprototype/core/SummaryPrompts.java")
        transcript = args.transcript.read_text()
        report.update({"runtime": "litert-lm-api 0.17.1", "backend": "CPU", "prompt_version": prompts["VERSION"],
                       "transcript_sha256": hashlib.sha256(transcript.encode()).hexdigest(), "transcript_word_count": len(transcript.split())})
        expected = json.loads(args.expected.read_text()) if args.expected else None
        report["fixture"] = expected or {"synthetic": "unknown", "evaluation": "No truth fixture supplied"}
        if expected and report["transcript_sha256"] != expected["sha256"]:
            raise ValueError("Transcript does not match expected fixture SHA-256")
        dump(args.output / "report.json", report)
        init = time.monotonic()
        if args.runtime_cache != ":nocache":
            Path(args.runtime_cache).mkdir(parents=True,exist_ok=True)
        with litert_lm.Engine(model_path=str(args.model), backend=litert_lm.Backend.CPU(thread_count=args.threads),
                             max_num_tokens=args.context, cache_dir=args.runtime_cache,
                             enable_benchmark=True, enable_speculative_decoding=False) as engine:
            report["model_initialization_seconds"] = round(time.monotonic()-init,3)
            report["transcript_measured_tokens"] = len(engine.tokenize(transcript))
            runner = Runner(args, engine, prompts)
            result = runner.run(transcript) if args.python_pipeline else runner.run_java(transcript)
            report.update(result)
            if expected:
                if not args.python_pipeline:
                    expected = adapt_expected_sources(expected,transcript,result["source_spans"])
                report["selected_fact_checks"] = {mode: evaluate(answer, expected) for mode, answer in result["finals"].items()}
            report["status"] = "completed"
    except Exception as error:
        report["status"] = "failed"
        report["error"] = f"{type(error).__name__}: {error}"
        print(report["error"], file=sys.stderr)
    finally:
        report["elapsed_seconds_this_invocation"] = round(time.monotonic()-start,3)
        report["peak_process_rss_kib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        report["calls"] = [{k:v for k,v in call.items() if k not in {"system", "input", "text", "answer"}} for call in runner.calls] if runner else []
        dump(args.output / "report.json", report)
    return 0 if report["status"] == "completed" else 1

if __name__ == "__main__":
    sys.exit(main())
