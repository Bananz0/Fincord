import enhanced_lrc as lrc

with lrc.db() as connection:
    changed = connection.execute(
        "UPDATE jobs SET status='needs_review' "
        "WHERE status='failed' AND message LIKE 'NeedsReview:%' AND attempts < ?",
        (lrc.MAX_ATTEMPTS,),
    ).rowcount
    changed += connection.execute(
        "UPDATE jobs SET status='needs_review',message='Quality gate requires human review' "
        "WHERE path IN (?,?) AND status='queued'",
        (
            "/data/media/music/(G)I‐DLE/I love (2022)/(G)I‐DLE - I love - 04 - Reset.mp3",
            "/data/media/music/(G)I‐DLE/I NEVER DIE (2022)/(G)I‐DLE - I NEVER DIE - 05 - POLAROID.mp3",
        ),
    ).rowcount
print(changed)
