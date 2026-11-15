#!/usr/bin/env bash
# Turns recorded clips (.mp4) into the small GIFs the README shows.
# Usage: tools/make_gifs.sh <folder with clips> <folder for GIFs>
# Each GIF gets a palette of its own made from the clip, which keeps text sharp and files small.
set -eu
from="${1:?folder with .mp4 clips}"
to="${2:?folder for the GIFs}"
width="${GIF_WIDTH:-360}"
fps="${GIF_FPS:-8}"
mkdir -p "$to"
for clip in "$from"/*.mp4; do
    name="$(basename "$clip" .mp4)"
    ffmpeg -v error -y -i "$clip" -vf \
        "fps=$fps,scale=$width:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle" \
        "$to/$name.gif"
    ls -l "$to/$name.gif"
done
