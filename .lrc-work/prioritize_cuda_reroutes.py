import enhanced_lrc as lrc

with lrc.db() as connection:
    changed = connection.execute(
        "UPDATE jobs SET updated_at=0 WHERE status='queued' AND required_lane='cuda'"
    ).rowcount
print(changed)
