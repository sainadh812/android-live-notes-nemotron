#!/usr/bin/env python3
"""Bounded REAL Gemma smoke; does not demonstrate long-transcript completion."""
import argparse
import dataclasses
import importlib.metadata
import json
from pathlib import Path
import platform
import resource
import time

from benchmark import MODEL_BYTES, MODEL_SHA256, digest_file, dump, response_text_and_thought_size

PROMPT = """[S0001] Orion pilot was provisionally November 12, with a proposed $120,000 budget.
[S0002] Final decision: November 19 replaces November 12. Approved budget is $95,000, replacing $120,000.
[S0003] Priya will update the risk register by October 9. Support ownership is unassigned."""
SYSTEM = "Summarize these meeting facts in at most 90 words. Preserve explicit corrections, action owners and deadlines. Keep unknown ownership unassigned. Do not invent facts."

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"status":"running", "synthetic":True, "phone_benchmark":False,
              "test_scope":"Short real-inference smoke only; not the 125-minute pipeline", "host":platform.platform(),
              "model_sha256":MODEL_SHA256, "runtime":"litert-lm-api 0.17.1", "backend":"CPU", "threads":4,
              "context":2048, "max_output_tokens":256, "thinking_budget_when_enabled":64,
              "system":SYSTEM, "input":PROMPT, "calls":[]}
    dump(args.output/"report.json", report)
    start = time.monotonic()
    try:
        if args.model.stat().st_size != MODEL_BYTES or digest_file(args.model) != MODEL_SHA256:
            raise ValueError("Model hash/size mismatch")
        if importlib.metadata.version("litert-lm-api") != "0.17.1":
            raise ValueError("Runtime version mismatch")
        import litert_lm
        init = time.monotonic()
        with litert_lm.Engine(model_path=str(args.model),backend=litert_lm.Backend.CPU(thread_count=4),
                             max_num_tokens=2048,cache_dir=":nocache",enable_benchmark=True,
                             enable_speculative_decoding=False) as engine:
            report["initialization_seconds"] = round(time.monotonic()-init,3)
            report["input_tokens_without_template"] = len(engine.tokenize(SYSTEM))+len(engine.tokenize(PROMPT))
            for thinking in (False,True):
                call = {"thinking":thinking,"status":"running"}
                report["calls"].append(call)
                dump(args.output/"report.json",report)
                print(f"START smoke thinking={thinking}",flush=True)
                before=time.monotonic()
                with engine.create_conversation(system_message=SYSTEM,max_output_tokens=256,
                       thinking_config=litert_lm.ThinkingConfig(enable_thinking=thinking,thinking_token_budget=64 if thinking else 0),
                       sampler_config=litert_lm.SamplerConfig(top_k=1,top_p=1.0,temperature=0.0,seed=0)) as conversation:
                    response=conversation.send_message(PROMPT)
                    call["text"],call["thinking_characters"]=response_text_and_thought_size(response)
                    try:
                        call["native_benchmark"]=dataclasses.asdict(conversation.get_benchmark_info())
                    except Exception as metric_error:
                        call["native_benchmark_error"]=str(metric_error)
                    call["status"]="completed"
                call["wall_seconds"]=round(time.monotonic()-before,3)
                call["peak_process_rss_kib"]=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
                dump(args.output/"report.json",report)
                print(json.dumps(call),flush=True)
        report["status"]="completed"
    except Exception as error:
        report["status"]="failed"
        report["error"]=f"{type(error).__name__}: {error}"
    finally:
        report["elapsed_seconds"]=round(time.monotonic()-start,3)
        report["peak_process_rss_kib"]=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        dump(args.output/"report.json",report)
    return 0 if report["status"]=="completed" else 1

if __name__ == "__main__":
    raise SystemExit(main())
