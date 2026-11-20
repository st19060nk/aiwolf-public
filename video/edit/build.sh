#!/usr/bin/env bash
# 研究紹介動画（video/aiwolf_demo_experience.mp4）を 2 本の録画から作り直す。
#   bash video/edit/build.sh <1 本目の録画.mp4> <2 本目の録画.mp4>
# テロップは同じフォルダの t*.txt（UTF-8）。ffmpeg が必要（winget install Gyan.FFmpeg）。
set -euo pipefail
F="${FFMPEG:-ffmpeg}"
# Git Bash 前提：ffmpeg に渡すパスは Windows 形式（C:/...）にし、フィルタ内ではコロンをエスケープする。
E="$(cd "$(dirname "$0")" && pwd -W)"
EE="${E/:/\\:}"
W="$(cygpath -m "$(mktemp -d)")"   # 途中の切り出しはここに置き、最後に消す
V1="$1"
V2="$2"
OUT="$E/../aiwolf_demo_experience.mp4"
FIG="$E/../fig3_individuality_1080p.png"
FONT="fontfile='C\:/Windows/Fonts/YuGothB.ttc'"
ENC=(-c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -r 30 -an)

cap() { # caption box at top, two lines
  echo "drawbox=x=0:y=ih-140:w=iw:h=140:color=black@0.82:t=fill,drawtext=${FONT}:expansion=none:textfile='${EE}/$1':fontsize=42:fontcolor=white:line_spacing=12:x=(w-text_w)/2:y=h-122"
}
V1BASE="crop=1280:1360:1280:32,scale=-2:1080,pad=1920:1080:(ow-iw)/2:0:color=black,setsar=1,drawbox=x=1080:y=0:w=400:h=85:color=black:t=fill"
V2BASE="scale=1920:1080,setsar=1"

seg() { # out src start dur basefilter caption
  "$F" -v error -y -ss "$3" -t "$4" -i "$2" -vf "$5,$(cap "$6")" "${ENC[@]}" "$W/$1"
}

# 0: title (4 s)
"$F" -v error -y -f lavfi -i color=c=black:s=1920x1080:d=4:r=30 -vf "drawtext=${FONT}:expansion=none:textfile='${EE}/t_title1.txt':fontsize=80:fontcolor=white:x=(w-text_w)/2:y=420,drawtext=${FONT}:expansion=none:textfile='${EE}/t_title2.txt':fontsize=40:fontcolor=0xCCCCCC:x=(w-text_w)/2:y=560" "${ENC[@]}" "$W/s0.mp4"
seg s1.mp4 "$V1" 4  9 "$V1BASE" t1.txt
seg s2.mp4 "$V1" 13 6  "$V1BASE" t2.txt
seg s3.mp4 "$V1" 45 11 "$V1BASE" t3.txt
seg s4.mp4 "$V2" 0  9  "$V2BASE" t4.txt
seg s5.mp4 "$V2" 10 9  "$V2BASE" t5.txt
seg s6.mp4 "$V2" 28 4.5 "$V2BASE" t6.txt
# 7: figure (15 s)
"$F" -v error -y -loop 1 -t 15 -i "$FIG" -vf "scale=1920:1080,setsar=1,drawbox=x=0:y=ih-110:w=iw:h=110:color=black@0.78:t=fill,drawtext=${FONT}:expansion=none:textfile='${EE}/t7.txt':fontsize=40:fontcolor=white:x=(w-text_w)/2:y=h-80" "${ENC[@]}" "$W/s7.mp4"
# 8: closing line (4 s)
"$F" -v error -y -f lavfi -i color=c=black:s=1920x1080:d=4:r=30 -vf "drawtext=${FONT}:expansion=none:textfile='${EE}/t8.txt':fontsize=50:fontcolor=white:x=(w-text_w)/2:y=(h-text_h)/2" "${ENC[@]}" "$W/s8.mp4"

: > "$W/list.txt"
for i in 0 1 2 3 4 5 6 7 8; do echo "file 's$i.mp4'" >> "$W/list.txt"; done
"$F" -v error -y -f concat -safe 0 -i "$W/list.txt" -c copy -movflags +faststart "$OUT"
rm -rf "$W"
echo "done: $OUT"
