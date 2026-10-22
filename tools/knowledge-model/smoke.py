"""Two synthetic uploads: compare new fact acquisition and old fact retention."""
from engine import write_json
from data import prepare_payload


DOCUMENTS = [
    {"source_id": "synthetic-station", "title": "合成站点资料 A", "revision": "1",
     "text": "澄岚站的校验码是 松柏47。\n琥珀桥的登记号是 星沙82。"},
    {"source_id": "synthetic-lab", "title": "合成实验室资料 B", "revision": "1",
     "text": "白榆实验室的样本代号是 青鹭63。\n紫汀仓库的通行词是 云舟29。"},
]


def run_smoke(runtime):
    results = []
    for document in DOCUMENTS:
        job, duplicate = runtime.store.enqueue(prepare_payload(document))
        if duplicate and job["status"] not in ("queued", "running"):
            results.append(job["result"] or job)
            if job["status"] != "published":
                break
            continue
        # Smoke requires an isolated data directory to keep upload ordering inspectable.
        claimed = runtime.store.claim()
        if not claimed or claimed["id"] != job["id"]:
            raise RuntimeError("Smoke data directory contains earlier queued uploads; use a fresh directory")
        result = runtime.run_job(claimed)
        results.append(result)
        if result["status"] != "published":
            break
    report = {"synthetic_data_only": True, "rounds": results,
              "active_version": runtime.store.active(),
              "passed": len(results) == 2 and all(r["status"] == "published" for r in results),
              "limits": "Four explicit synthetic facts cannot establish mastery of real multi-domain documents."}
    write_json(runtime.store.root / "smoke-report.json", report)
    return report
