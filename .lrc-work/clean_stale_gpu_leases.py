import enhanced_lrc as lrc

with lrc.db() as connection:
    changed = connection.execute(
        "DELETE FROM gpu_leases WHERE NOT EXISTS ("
        "SELECT 1 FROM jobs WHERE jobs.path=gpu_leases.path AND jobs.status='leased' "
        "AND jobs.leased_by=gpu_leases.worker)"
    ).rowcount
print(changed)
