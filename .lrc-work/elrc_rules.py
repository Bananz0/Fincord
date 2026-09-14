"""
The alignment rules. One copy, because there were three and they had drifted apart.

**Canonical location: Bananz0/fincord-lyrics-studio.** Accord carries a verbatim copy at
`.lrc-work/elrc_rules.py` so the Dockerised service can import it without a build-time
dependency on another repository, following the same convention this tree already uses for
its vendored media3 classes. Do not edit that copy in place - change this one and re-copy,
or the drift this module exists to end starts again.

Everything here is pure Python with no dependencies, so the desktop app, the batch runner
and the Dockerised service can all hold the same opinion about what a correctly timed
lyric line looks like. That was not true before this module existed:

* the batch runner bounded its CTC window without the quarter-second of slack the other
  two allowed, and without clamping to the track duration;
* only the desktop app clamped a line's end to the next line's start, so the other two
  could emit `...<00:14.65>` immediately followed by `[00:14.36]`;
* each had a different gate. The runner checked the flash-word rate and nobody else did;
  the service checked interior holds on short lines and nobody else did; the app checked
  neither and was therefore the most permissive of the three, which is exactly backwards
  for the one with a human watching it.

The gate here is the **union** of all three, so folding them together tightens every
caller rather than averaging them.

## The defect this exists for

The generator handed each line the whole span up to the next line's start, and forced
alignment placed the last word it recognised anywhere in that span:

    [00:01.47] <00:01.47>I <00:02.94>know <00:11.54>you <00:12.74>do<00:12.78>

Four words over eleven seconds, `know` holding 8.6 s of an instrumental the singer spends
silent. The phrase is sung inside three. In the player the highlight parks on `know` while
`you do` stays dark, then lights both exactly as the next line begins - which is why a line
appears to advance a line late. The same shape was found in 12,511 short lines across
5,428 of 9,710 files, and it is not repairable after the fact: the true time of `you` is
not in the file and cannot be recovered from it.

## Why the gate reads words and not the rendered file

Enhanced LRC stores only word *starts*. A word's end is inferred from the next marker, so
a finished file cannot distinguish a two-second held note from a half-second word followed
by silence. Judging output that way rejected good tracks for singing slowly. Every check
that can run against word objects does; `stretched_word_in_rendered` exists only for
auditing a library whose alignment is long gone.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# --------------------------------------------------------------------------------------
# constants

# A gap this long between one word ending and the next beginning is not phrasing, it is an
# instrumental. The line is closed and a new one opened.
LINE_SPLIT_GAP_MS = 1200

# How long a line could plausibly take to sing: a base allowance plus a share per word.
# Two thirds of a second a word plus slack is generous for sung delivery and still an
# order of magnitude tighter than an instrumental.
PLAUSIBLE_BASE_S = 2.0
PLAUSIBLE_PER_WORD_S = 0.7

# A single word held longer than this is alignment smearing rather than singing. Held
# notes are real and common - "ooooh" across two seconds is ordinary - so this sits well
# above them; the defect being guarded against gave one word 8.6 seconds.
MAX_WORD_MS = 5000

# A word may legitimately be held. What is not legitimate is a short line, sung as one
# phrase, whose interior words are pulled apart.
MAX_INTERIOR_HOLD_MS = 3000
SHORT_LINE_WORDS = 8

# Words too short to have been sung, as a fraction of the track.
#
# Ten percent, not two. Two was picked before there was any evidence and rejected one
# track in seven at rates of 2-4% - which is not alignment failing, it is alignment being
# precise about fast syllables, and a word rendered with a very short span simply means
# the highlight crosses it quickly, which is what should happen. Genuine collapse looks
# like forty percent and is still caught. The defect worth refusing a track over is the
# stretched word, and that is checked separately and exactly.
MAX_FLASH_RATE = 0.10
MIN_MS_PER_CHAR = 20
MIN_WORD_MS_FLOOR = 30


@dataclass
class Word:
    text: str
    start_ms: int
    end_ms: int

    @property
    def duration_ms(self) -> int:
        return self.end_ms - self.start_ms


def as_words(raw: list) -> list[Word]:
    """
    Accept the shapes the three callers already use.

    The batch runner carries dicts of `{word, start, end}` in milliseconds, whisperx
    returns `{word, start, end}` in seconds, and the app builds Word directly. Converting
    at the boundary is cheaper than making all three agree on a representation.
    """
    out: list[Word] = []
    for item in raw:
        if isinstance(item, Word):
            out.append(item)
            continue
        text = (item.get('word') or item.get('text') or '').strip()
        start, end = item.get('start'), item.get('end')
        if not text or start is None or end is None:
            continue
        # Seconds or milliseconds: whisperx emits floats in seconds, the runner ints in
        # milliseconds. A float is the reliable tell, since a track is never 3,000 hours.
        if isinstance(start, float) or isinstance(end, float):
            start, end = start * 1000.0, end * 1000.0
        out.append(Word(text, int(round(start)), int(round(end))))
    return out


# --------------------------------------------------------------------------------------
# timing


def stamp(ms: int) -> str:
    """
    `mm:ss.cc`, the Enhanced LRC cue format, from milliseconds.

    Rounds to the nearest centisecond rather than truncating. Two of the three callers
    truncated, which biases every cue early by up to 10 ms - always in the same direction,
    so it does not average out across a line the way a rounding error does. Ten
    milliseconds is inaudible on its own; a systematic early lean on every word in a
    library being regenerated for timing accuracy is worth not introducing.
    """
    centiseconds = max(0, round(max(0, int(ms)) / 10))
    minutes, centiseconds = divmod(centiseconds, 6000)
    secs, centiseconds = divmod(centiseconds, 100)
    return f'{minutes:02d}:{secs:02d}.{centiseconds:02d}'


def stamp_seconds(seconds: float, brackets: str = '<>') -> str:
    """The same cue from seconds, wrapped. The service works in seconds throughout."""
    open_b, close_b = brackets[0], brackets[1]
    return f'{open_b}{stamp(int(round(seconds * 1000)))}{close_b}'


def window_for(line_start_s: float, word_count: int, next_start_s: float | None,
               duration_s: float | None = None) -> tuple[float, float]:
    """
    The span of audio a line's words may be placed in.

    The first version of this ran to the next line's start, on the theory that CTC would
    leave the surplus as blanks. It does not, reliably: where the acoustic evidence for a
    word is weak the aligner places it anywhere in the window that scores best. Given
    [1.47, 12.79] for "I know you do" it put `do` at 12.38 - reproducing, from the
    opposite direction, the exact defect this module exists to remove.

    So the window is the shorter of the next line's start and what the line could plausibly
    take to sing. The quarter-second past the next line's start, and the lookback before
    this one, are slack for a singer who comes in early or late; they are small enough that
    an instrumental never fits inside them.
    """
    plausible = (line_start_s + PLAUSIBLE_BASE_S
                 + PLAUSIBLE_PER_WORD_S * max(word_count, 1))
    end = plausible if next_start_s is None else min(next_start_s + 0.25, plausible)
    end = max(line_start_s + 0.5, end)
    if duration_s is not None:
        end = min(duration_s, end)
    return max(0.0, line_start_s - 0.45), end


def split_on_gaps(words: list[Word]) -> list[list[Word]]:
    """
    Close a line wherever the singing actually stopped.

    A word's end comes from the alignment, so silence between phrases is visible rather
    than inferred. Where one opens up the line is split - the only honest thing Enhanced
    LRC can express, since its markers say "a word starts here" and nothing else.
    """
    groups: list[list[Word]] = []
    current: list[Word] = []
    for w in words:
        if current and (w.start_ms - current[-1].end_ms) >= LINE_SPLIT_GAP_MS:
            groups.append(current)
            current = []
        current.append(w)
    if current:
        groups.append(current)
    return groups


def render(groups: list[list[Word]]) -> list[str]:
    """
    Groups of words as Enhanced LRC lines.

    A line's trailing marker is clamped to the next line's start. Alignment windows
    overlap by design - each line gets a little room before and after itself - so two
    adjacent lines can come back with the earlier ending after the later begins
    (`...<00:14.65>` then `[00:14.36]`, observed in a real run). Left alone that makes the
    last word of one line appear to still be singing while the next has started, which is
    the same defect as a stretched word spread across a line boundary.
    """
    groups = [g for g in groups if g]
    lines = []
    for index, words in enumerate(groups):
        body = ''.join(
            f'<{stamp(w.start_ms)}>{w.text}' + ('' if i == len(words) - 1 else ' ')
            for i, w in enumerate(words)
        )
        end_ms = words[-1].end_ms
        if index + 1 < len(groups):
            # Clamp to the next line, but never so hard that the last word ends where it
            # started. Two adjacent lines can be aligned to the same instant, and clamping
            # to the bare start produced `<00:14.36>down<00:14.36>` in a real run - a word
            # with no duration at all, which the highlight crosses without ever showing.
            # A few milliseconds of overlap into the next line is imperceptible; a word
            # that never lights up is not.
            floor = words[-1].start_ms + MIN_WORD_MS_FLOOR
            end_ms = max(floor, min(end_ms, groups[index + 1][0].start_ms))
        lines.append(f'[{stamp(words[0].start_ms)}] {body}<{stamp(end_ms)}>')
    return lines


def header(artist: str, album: str, title: str) -> list[str]:
    return [
        f'[ar:{artist}]',
        f'[al:{album}]',
        f'[ti:{title}]',
        '[by:Embedded by Glen Muthoka (@bananz0)]',
    ]


# --------------------------------------------------------------------------------------
# the gate


def gate(groups: list[list[Word]]) -> str | None:
    """
    Why this must not be written, or None.

    The union of the three gates that existed before this module. Each caller previously
    enforced a different subset, so a track the service would quarantine could be written
    by the app; folding them together means every caller now enforces all of it.
    """
    total = flashes = 0
    for words in groups:
        for i, w in enumerate(words):
            total += 1
            duration = w.duration_ms
            if duration < 0:
                return f'non-monotonic: "{w.text}" ends before it starts'
            if duration > MAX_WORD_MS:
                return f'stretched-word: "{w.text}" held {duration / 1000:.1f}s'
            need = max(MIN_WORD_MS_FLOOR, MIN_MS_PER_CHAR * max(len(w.text), 1))
            if duration < need:
                flashes += 1
            if i + 1 < len(words):
                gap = words[i + 1].start_ms - w.end_ms
                if gap >= LINE_SPLIT_GAP_MS:
                    return f'unsplit gap of {gap / 1000:.1f}s inside a line'

        # Interior holds on a short line, which the service checked and nobody else did.
        # Only interior gaps count, and only on short lines: the span from the last word to
        # the line's closing marker is a legitimate way to say the line ended, and a line
        # of many words is more often a spoken passage than a mistake.
        if 2 <= len(words) <= SHORT_LINE_WORDS:
            interior = [words[i + 1].start_ms - words[i].start_ms
                        for i in range(len(words) - 1)]
            if interior and max(interior) >= MAX_INTERIOR_HOLD_MS:
                return (f'a word is held {max(interior) / 1000:.1f}s inside a '
                        f'{len(words)}-word line')

    if total:
        allowed = max(3, int(total * MAX_FLASH_RATE))
        if flashes > allowed:
            return (f'flash-word: {flashes} of {total} words too short '
                    f'(allowed {allowed})')
    return None


_CUE = re.compile(r'<(\d+):(\d{2})[.:](\d{2,3})>')


def stretched_word_in_rendered(rendered: list[str]) -> str | None:
    """
    The interior-hold check against finished text rather than word objects.

    Strictly weaker than `gate`, and only for auditing a library nobody has the alignment
    for any more - a rendered file cannot tell a held note from a word followed by silence.
    Prefer `gate` wherever the words are still in hand.
    """
    for line in rendered:
        cues = [int(mm) * 60000 + int(ss) * 1000 + int(fr) * 10 ** (3 - len(fr))
                for mm, ss, fr in _CUE.findall(line)]
        words = len(cues) - 1          # the final cue closes the line
        if words < 2 or words > SHORT_LINE_WORDS:
            continue
        interior = [cues[i + 1] - cues[i] for i in range(len(cues) - 2)]
        if interior and max(interior) >= MAX_INTERIOR_HOLD_MS:
            held = max(interior) / 1000
            return (f'A word is held {held:.1f}s inside a {words}-word line, which is an '
                    f'instrumental gap charged to a word rather than the line ending')
    return None
