#!/usr/bin/env python3
"""Query the actual measured interval of an Android Macrobenchmark Perfetto trace."""
import argparse
import json
from perfetto.trace_processor import TraceProcessor

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("trace")
args = parser.parse_args()
with TraceProcessor(trace=args.trace) as processor:
    def query(sql):
        return [row.__dict__ for row in processor.query(sql)]

    bounds = query("SELECT ts,dur FROM slice WHERE name='measureBlock' AND dur>0 ORDER BY ts LIMIT 1")
    if not bounds:
        raise SystemExit("No Macrobenchmark measureBlock found; refusing to mix setup and measurement")
    start, end = bounds[0]["ts"], bounds[0]["ts"] + bounds[0]["dur"]
    slices = query(f"""
        SELECT t.name AS thread,
            CASE WHEN s.name LIKE 'Choreographer#doFrame%' THEN 'Choreographer#doFrame' ELSE s.name END AS name,
            count(*) AS count, round(sum(s.dur)/1e6,2) AS inclusive_ms,
            round(max(s.dur)/1e6,2) AS max_ms
        FROM slice s JOIN thread_track tt ON s.track_id=tt.id
            JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid
        WHERE p.name='dev.amatv' AND s.dur>0 AND s.ts>={start} AND s.ts<{end}
            AND (t.is_main_thread OR t.name IN ('RenderThread','AMATV-lava'))
        GROUP BY 1,2 ORDER BY inclusive_ms DESC LIMIT 35
    """)
    frames = query(f"""
        SELECT f.layer_name,f.jank_type,count(*) AS count,
            round(avg(f.dur)/1e6,2) AS mean_ms,round(max(f.dur)/1e6,2) AS max_ms
        FROM actual_frame_timeline_slice f JOIN process p ON f.upid=p.upid
        WHERE p.name='dev.amatv' AND f.ts>={start} AND f.ts<{end}
        GROUP BY 1,2 ORDER BY count DESC
    """)
    print(json.dumps({"measure_ms": (end-start)/1e6, "slice_totals_are_inclusive": True,
                      "slices": slices, "frame_timeline": frames}, indent=2))
