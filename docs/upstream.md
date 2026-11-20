# Mate and AARE (github.com/prototux/AARE)

Mate grew out of AARE. To keep the two from drifting apart for good, this page says where Mate stands against AARE.
The phone app shows the same under its pages (`aareBase` and `aareSeen` in `gradle.properties`).

## Base

AARE **1.4.0+58**, commit `5d932ae` (merge of #120, 10.11.2026): Mate's first commit `2a7d2ca` matches its tree in all
518 files. Found by comparing the files of the first commit with each AARE commit of those days.

## Taken over (AARE release, commit, what)

| AARE | Commit | What | In Mate |
|---|---|---|---|
| 1.5.0 | 966165c (#152) | USB: no crash at the end of a session when the accessory permission is gone | d5c774f |
| 1.5.0 | 630122c (#182) | USB: wait for the head unit to come back after a drop, log how the link died | d5c774f |
| 1.5.0 | c2c16df | Wireless: open the car's Wi-Fi projection channel | 476ebc0 |

## Looked through, up to AARE 1.5.0 (14.11.2026) - candidates not taken over yet

Mate has changed much of the car screen, the audio path and the apps since; each of these needs a look of its own.

- Car audio: 7cd6470 (held acks are no stall), d37d3d6 / de1dda8 (dropped chunks and stream lines in the log),
  f2ed03f (~200 ms ahead at a stream start), 670a283 (close the media stream when focus is lost mid-stream),
  6a19b77 (Android Auto's chunk sizes and timestamps), bb5b7ad (stop a Shizuku capture whose reader went away)
- Video: fd64a44 (ask for focus again), 1654f4f (keyframe on projected focus), cc1c0c4 (configuration by the shown
  size), fbe6bcd (redraw when the encoder stops), b324316 (constant bitrate option)
- Calls: 9280e74 (option to use the phone's speaker and microphone in the car)
- Wireless: e9168cf (keep the LAN listener on)
- Car apps: 93dfd74 (give up on an app that never answers), 188ae39 (rebuild a map surface on resize),
  c994c80 (navigation only with the template permission), 95a1e39 (permissions asked on the phone), 3acf73d (icon tint)
- Navigation status: 4e04492 (protocol 1.5 head units only get what they read)
- Certificates: 2da06e8, 8418271, 5f00954, 9c68c0c, a793e0d
- Car: 7ad0813 (media keys before a car screen), b437ac5 (log the services left closed); vehicles.json 9b66d76
- Not for Mate (its own car screen): AARE's themes, layouts, dashboard and launcher changes, README and docs.

## How to compare again

    git clone --filter=blob:none https://github.com/prototux/AARE aare
    git -C aare log --no-merges --format='%h %s' 5d932ae..<new tag>

Take a change over with `git format-patch` there and `git am -3` here, then note it in the table and raise `aareSeen`.
