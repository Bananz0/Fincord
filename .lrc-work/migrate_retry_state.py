#!/usr/bin/env python3
"""One-time live queue migration for capability routing and monotonic retries."""

import json
import time

import enhanced_lrc as lrc
import enhanced_lrc_gpu_api as gpu


lrc.initialise()
gpu.initialise()
now = int(time.time())
with lrc.db() as connection:
    before = {
        row["status"]: row["count"]
        for row in connection.execute("SELECT status,COUNT(*) AS count FROM jobs GROUP BY status")
    }
    connection.execute(
        "UPDATE jobs SET status='queued',leased_by=NULL,leased_until=NULL,updated_at=? "
        "WHERE status IN ('leased','processing')",
        (now,),
    )
    connection.execute("DELETE FROM gpu_leases")
    poison = connection.execute(
        "UPDATE jobs SET status='queued',required_lane='cuda',leased_by=NULL,leased_until=NULL,updated_at=? "
        "WHERE message LIKE '%padded_input_ids.get_size() >= tokens.size()%' "
        "OR message LIKE '%Result does not carry the Accord generator marker%'",
        (now,),
    ).rowcount
    terminal = connection.execute(
        "UPDATE jobs SET status='failed',leased_by=NULL,leased_until=NULL,updated_at=? "
        "WHERE attempts>=? AND status='queued'",
        (now, lrc.MAX_ATTEMPTS),
    ).rowcount
    after = {
        row["status"]: row["count"]
        for row in connection.execute("SELECT status,COUNT(*) AS count FROM jobs GROUP BY status")
    }
print(json.dumps({"before": before, "poison_routed_cuda": poison, "terminal_failed": terminal, "after": after}))
