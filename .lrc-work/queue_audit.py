import json
import enhanced_lrc as lrc

with lrc.db() as connection:
    result = {
        "attempts": [dict(row) for row in connection.execute(
            "SELECT attempts,COUNT(*) AS count FROM jobs GROUP BY attempts ORDER BY attempts"
        )],
        "required_lanes": [dict(row) for row in connection.execute(
            "SELECT required_lane,COUNT(*) AS count FROM jobs WHERE required_lane IS NOT NULL GROUP BY required_lane"
        )],
        "leases": [dict(row) for row in connection.execute(
            "SELECT worker,jobs.required_lane,jobs.path FROM gpu_leases JOIN jobs USING(path) ORDER BY worker"
        )],
        "recent_complete": [dict(row) for row in connection.execute(
            "SELECT path,source,attempts FROM jobs WHERE status='complete' ORDER BY updated_at DESC LIMIT 12"
        )],
    }
print(json.dumps(result, ensure_ascii=False))
