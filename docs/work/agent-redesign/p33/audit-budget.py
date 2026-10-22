"""Read-only post-experiment audit and budget replay. Does not call any model."""
import importlib.util
import json
from pathlib import Path
import statistics
import sys
import time

sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("budget_experiment",HERE/"budget-experiment.py")
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
OUT=module.OUT

def main():
    comparison=json.loads((OUT/"api-local-comparison.json").read_text(encoding="utf-8"))
    cache=json.loads((module.CACHE/(comparison["fixedSha"]+"-remote-files.json")).read_text(encoding="utf-8"))
    audit={"scope":"Offline Git/API evidence audit; no model requests, no production enforcement proof","apiCachedFiles":[],"tasks":[],"gitGrepTimings":[]}
    for key,body in sorted(cache.items()):
        sha,path=key.split("|",1)
        local=module.git("show",sha+":"+path)
        entry={"sha":sha,"path":path,"apiHash":module.sha256(body["content"]),"gitHash":module.sha256(local),"equal":body["content"]==local}
        if not entry["equal"]:raise RuntimeError("cached_api_content_mismatch")
        audit["apiCachedFiles"].append(entry)
    for task in ["cancel","report","commits","output-budget"]:
        report=json.loads((OUT/(task+".json")).read_text(encoding="utf-8"))
        calls=report["calls"]
        settled=0;peak=0;reserved_sum=0
        for c in calls:
            reserve=c["inputEstimate"]+report["configuration"]["maxOutputTokens"]
            peak=max(peak,settled+reserve);reserved_sum+=reserve
            usage=c.get("usage")
            settled+=(usage.get("prompt_tokens",0)+usage.get("completion_tokens",0)) if usage else reserve
        result={"task":task,"stopReason":report["stopReason"],"checks":report["checks"],"rounds":len(calls),"tools":len(report["tools"]),"maxRequestBytes":report["summary"]["maxRequestBytes"],"maxInputEstimate":report["summary"]["maxInputEstimate"],"maxResponseMillis":max(c["durationMillis"] for c in calls),"maxOutputTokens":max((c.get("usage")or{}).get("completion_tokens",0)for c in calls),"actualTokens":settled,"peakReservationWithUsage":peak,"reservationIfAllUsageMissing":reserved_sum,"totalMillis":report["totalMillis"],"repeatRequests":report["repeatToolRequests"]}
        # Replay only: these are not task executions or proof that the production guard works.
        result["replay"]={"within32Rounds":len(calls)<=32,"within48Rounds":len(calls)<=48,"within64NetworkAttempts":len(calls)<=64,"within128Tools":len(report["tools"])<=128,"within20Minutes":report["totalMillis"]<=1200000,"within120SecondsEach":result["maxResponseMillis"]<=120000,"within8192Output":result["maxOutputTokens"]<=8192,"oneMillionWithUsageWouldFit":peak<=1000000,"oneMillionWithoutUsageWouldFit":reserved_sum<=1000000}
        result["replay"].update(within96NetworkAttempts=len(calls)<=96,within192Tools=len(report["tools"])<=192,within16384Output=result["maxOutputTokens"]<=16384)
        audit["tasks"].append(result)
    negative=json.loads((OUT/"commits-without-pagination.json").read_text(encoding="utf-8"))
    last=None;streak=0;stop=None
    for call in negative["calls"]:
        profile=[(t["function"]["name"],json.loads(t["function"]["arguments"]))for t in call["response"].get("tool_calls",[])]
        if profile==last:streak+=1
        else:last=profile;streak=1
        if streak==3 and stop is None:stop=call["turn"]
    audit["negativeReplay"]={"source":"commits-without-pagination.json","stopReason":negative["stopReason"],"rounds":len(negative["calls"]),"repeatedToolRequests":negative["repeatToolRequests"],"firstThreeIdenticalToolRounds":stop,"scope":"request-profile replay only; production no-progress detection must also check identical return hashes and no newly sent material"}
    material_checks=[]
    for task in ["cancel","report","commits","output-budget"]:
        report=json.loads((OUT/(task+".json")).read_text(encoding="utf-8"));seen=set()
        for tool in report["tools"]:
            result=tool["result"]
            mats=([result["material"]]if result.get("material")else[])+result.get("materials",[])+result.get("matches",[])
            for m in mats:
                if m["id"]in seen:continue
                seen.add(m["id"])
                expected="\n".join(module.git("show",m["sha"]+":"+m["path"]).splitlines()[m["startLine"]-1:m["endLine"]])
                equal=expected==m["content"]
                extra_context=False
                if not equal:
                    original=module.git("show",m["sha"]+":"+m["path"]).splitlines()
                    extra_context=m["endLine"]<len(original) and m["content"]==expected+"\n"+original[m["endLine"]]
                    if not extra_context:raise RuntimeError("actual_tool_material_mismatch")
                material_checks.append({"task":task,"id":m["id"],"sha":m["sha"],"path":m["path"],"startLine":m["startLine"],"endLine":m["endLine"],"declaredRangeEqualsLocalOriginal":True,"strictEnvelopeRange":equal,"historicalExtraTrailingContextLine":extra_context})
    audit["actualToolMaterials"]=material_checks
    audit["historicalExtraContextEnvelopes"]=sum(m["historicalExtraTrailingContextLine"]for m in material_checks)
    audit["historicalMaterialNote"]="Original diff-material experiments included one extra original context line after the declared end. Citation validation never allowed that extra line. Original evidence is retained, generator slicing fixed, and corrected diffs verified without further model calls."
    scope="backend/src/main/java/com/codereview/agent"
    for query in ["getCancelRequestedAt","requireActive","source_snapshot","__P33_NO_MATCH_20261022__"]:
        timings=[]
        for i in range(10):
            start=time.monotonic();module.git("grep","-n","-F","-e",query,comparison["fixedSha"],"--",":(literal)"+scope,ok=(0,1));timings.append(round((time.monotonic()-start)*1000,2))
        audit["gitGrepTimings"].append({"query":query,"samples":10,"medianMillis":statistics.median(timings),"maxMillis":max(timings),"minMillis":min(timings),"scope":"local fixed-SHA Git object search on this Windows host; not remote search or cold mirror synchronization"})
    window=1048576;output=8192;input_cap=int(window*.8);safety=max(1024,int(window*.1+.9999))
    fits=lambda i:i<input_cap and i+output+safety<=window
    audit["contextArithmeticReplay"]={"window":window,"inputCapExclusive":input_cap,"outputReserve":output,"safetyReserve":safety,"oneBelowAccepted":fits(input_cap-1),"atThresholdRejected":not fits(input_cap),"scope":"arithmetic replay, not a full-window provider request"}
    if not audit["contextArithmeticReplay"]["oneBelowAccepted"] or not audit["contextArithmeticReplay"]["atThresholdRejected"]:raise RuntimeError("boundary_error")
    module.save(OUT/"audit-and-replay.json",audit)
    print("AUDIT",len(audit["apiCachedFiles"]),"API file versions equal local Git; tasks:")
    for t in audit["tasks"]:print(t["task"],t["stopReason"],"rounds",t["rounds"],"tools",t["tools"],"seconds",round(t["totalMillis"]/1000,2),"actualTokens",t["actualTokens"],"peakReserve",t["peakReservationWithUsage"],"unknownUsageReserve",t["reservationIfAllUsageMissing"])

if __name__=="__main__":main()
