#!/usr/bin/env bash
# ============================================================
# Two-Part Video Recreator (Overlay-less)
# ------------------------------------------------------------
# ✔ video.mp4 এর audio বাদ
# ✔ audio.mp3 এর প্রথম 11s → video.mp4 (auto speed adjust)
#     - video.mp4 > 11s হলে → স্পিড আপ (exact 11s হবে)
#     - video.mp4 < 11s হলে → স্লো ডাউন (exact 11s হবে)
#     - audio < 11s হলে → পুরো audio জুড়ে video চলবে
# ✔ audio.mp3 এর বাকি অংশ → endvideo.mp4
#     - endvideo বড় হলে → trim
#     - endvideo ছোট হলে → loop
#     - endvideo normal speed (কোনো speed পরিবর্তন নেই)
# ✔ Final output: high quality (CRF 12, veryslow, yuv420p)
# ============================================================

set -euo pipefail

# ---------- কনফিগ ----------
WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
VIDEO_INPUT="$WORKSPACE/video/video.mp4"
ENDVIDEO_INPUT="$WORKSPACE/video/endvideo.mp4"
AUDIO_INPUT="$WORKSPACE/audio/audio.mp3"
OUTPUT_DIR="$WORKSPACE/output"

FIRST_SEG_TARGET=10        # প্রথম অংশের টার্গেট (সেকেন্ড)

# ---------- FFmpeg চেক ----------
if ! command -v ffmpeg >/dev/null 2>&1 || ! command -v ffprobe >/dev/null 2>&1; then
    echo "🔧 FFmpeg নেই, ইনস্টল করছি..."
    sudo apt-get update -y
    sudo apt-get install -y ffmpeg
fi
echo "✅ FFmpeg: $(ffmpeg -version | head -n1)"

# ---------- ইনপুট চেক ----------
for f in "$VIDEO_INPUT" "$ENDVIDEO_INPUT" "$AUDIO_INPUT"; do
    if [ ! -f "$f" ]; then
        echo "❌ পাওয়া যায়নি: $f"
        exit 1
    fi
done
mkdir -p "$OUTPUT_DIR"

# ---------- helper: duration ----------
get_dur() {
    ffprobe -v 0 -of csv=p=0 -show_entries format=duration "$1" 2>/dev/null || echo ""
}

AUDIO_DURATION=$(get_dur "$AUDIO_INPUT")
VIDEO_DURATION=$(get_dur "$VIDEO_INPUT")
ENDVIDEO_DURATION=$(get_dur "$ENDVIDEO_INPUT")

[ -z "$AUDIO_DURATION" ]    && { echo "❌ audio.mp3 duration পাওয়া যায়নি"; exit 1; }
[ -z "$VIDEO_DURATION" ]    && { echo "❌ video.mp4 duration পাওয়া যায়নি"; exit 1; }
[ -z "$ENDVIDEO_DURATION" ] && { echo "❌ endvideo.mp4 duration পাওয়া যায়নি"; exit 1; }

# ---------- audio stream আছে কিনা চেক ----------
if ! ffprobe -v error -select_streams a:0 -show_entries stream=index \
        -of csv=p=0 "$AUDIO_INPUT" 2>/dev/null | grep -q .; then
    echo "❌ audio.mp3 এ কোনো audio stream নেই"
    exit 1
fi

# ---------- resolution + fps (video.mp4 থেকে) ----------
W=$(ffprobe -v 0 -of csv=p=0 -select_streams v:0 -show_entries stream=width  "$VIDEO_INPUT" 2>/dev/null || echo "")
H=$(ffprobe -v 0 -of csv=p=0 -select_streams v:0 -show_entries stream=height "$VIDEO_INPUT" 2>/dev/null || echo "")
if [ -z "$W" ] || [ "$W" = "0" ]; then W=1920; fi
if [ -z "$H" ] || [ "$H" = "0" ]; then H=1080; fi
W=$(( W - W % 2 ))
H=$(( H - H % 2 ))

FPS=$(ffprobe -v 0 -of csv=p=0 -select_streams v:0 \
    -show_entries stream=r_frame_rate "$VIDEO_INPUT" 2>/dev/null \
    | awk -F'/' '{ if ($2) printf "%.6f", $1/$2; else print $1 }')
if [ -z "$FPS" ] || [ "$FPS" = "0" ]; then FPS=30; fi

echo "─────────────────────────────────────────────"
echo "📐 Resolution : ${W}x${H}"
echo "🎞️  FPS        : $FPS"
echo "⏱️  Durations  : video=${VIDEO_DURATION}s | endvideo=${ENDVIDEO_DURATION}s | audio=${AUDIO_DURATION}s"
echo "─────────────────────────────────────────────"

# ---------- হিসাব ----------
# প্রথম অংশের দৈর্ঘ্য = min(11, audio_duration)
# অর্থাৎ audio যদি ১১ সেকেন্ডের কম হয়, তবে পুরো audio টাই প্রথম অংশে যাবে।
FIRST_SEG_DURATION=$(awk -v a="$AUDIO_DURATION" -v t="$FIRST_SEG_TARGET" \
    'BEGIN { print (a < t) ? a : t }')

# video.mp4 কে FIRST_SEG_DURATION এ আনতে হবে (exact)
# setpts=PTS*ratio  →  new_dur = source_dur * ratio
# ratio = FIRST_SEG_DURATION / VIDEO_DURATION
#   - video > target → ratio < 1 → speed up
#   - video < target → ratio > 1 → slow down
PTS_RATIO=$(awk -v t="$FIRST_SEG_DURATION" -v v="$VIDEO_DURATION" \
    'BEGIN {
        if (v <= 0) { print "1.0"; exit }
        printf "%.8f", t/v
    }')

# বাকি অডিওর দৈর্ঘ্য
REMAIN_AUDIO=$(awk -v a="$AUDIO_DURATION" -v f="$FIRST_SEG_DURATION" \
    'BEGIN { r = a - f; if (r < 0) r = 0; printf "%.6f", r }')

TOTAL_DURATION=$(awk -v f="$FIRST_SEG_DURATION" -v r="$REMAIN_AUDIO" \
    'BEGIN { printf "%.6f", f + r }')

# স্পিডের দিক হিসাব (শুধু লগের জন্য)
SPEED_NOTE=$(awk -v v="$VIDEO_DURATION" -v t="$FIRST_SEG_DURATION" \
    'BEGIN {
        if (v > t + 0.05)      print "⚡ speed-up";
        else if (v < t - 0.05) print "🐢 slow-down";
        else                    print "➡️  normal";
    }')

echo "🎯 First segment   : ${FIRST_SEG_DURATION}s  (PTS ratio: $PTS_RATIO → $SPEED_NOTE)"
echo "🎯 Remaining audio : ${REMAIN_AUDIO}s"
echo "🎯 Total duration  : ${TOTAL_DURATION}s"

# endvideo status লগ
if awk -v e="$ENDVIDEO_DURATION" -v r="$REMAIN_AUDIO" 'BEGIN{exit !(e >= r)}'; then
    echo "🎯 endvideo        : trim হবে → ${REMAIN_AUDIO}s"
else
    echo "🎯 endvideo        : loop হবে → ${REMAIN_AUDIO}s"
fi
echo "─────────────────────────────────────────────"

# ---------- Filter graph নির্মাণ ----------
# সব ভিডিও স্ট্রিমকে একই pix_fmt / SAR / size এ normalise করা হচ্ছে (concat safe)
SCALE_PAD="scale=$W:$H:force_original_aspect_ratio=decrease,pad=$W:$H:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p"

# ইনপুট #0 = video.mp4 → setpts দিয়ে exact FIRST_SEG_DURATION এ আনা
FG="[0:v]setpts=PTS*${PTS_RATIO},fps=${FPS},${SCALE_PAD}[v0];"

# ইনপুট #1 = endvideo.mp4 (stream_loop -1 দিয়ে loop করা) → trim করে REMAIN_AUDIO
# normal speed, কোনো setpts পরিবর্তন নেই
if awk -v r="$REMAIN_AUDIO" 'BEGIN{exit !(r > 0.05)}'; then
    FG="${FG}[1:v]fps=${FPS},${SCALE_PAD},trim=end=${REMAIN_AUDIO},setpts=PTS-STARTPTS[v1];"
    FG="${FG}[v0][v1]concat=n=2:v=1:a=0[vc];"
else
    # audio ১১ সেকেন্ডের কম → endvideo লাগবে না, শুধু video.mp4
    FG="${FG}[v0]null[vc];"
fi

# final format
FG="${FG}[vc]format=yuv420p[out]"

# ---------- Inputs সাজানো ----------
# 0 = video.mp4
# 1 = endvideo.mp4  (loop)
# 2 = audio.mp3
INPUTS=(
    -i "$VIDEO_INPUT"
    -stream_loop -1 -i "$ENDVIDEO_INPUT"
    -i "$AUDIO_INPUT"
)
AUDIO_IDX=2

# ---------- Final encode (Single-pass) ----------
echo "🎬 Encoding final video (CRF 12, veryslow, yuv420p, high profile)..."
ffmpeg -hide_banner -loglevel warning -stats \
    "${INPUTS[@]}" \
    -filter_complex "$FG" \
    -map "[out]" -map "${AUDIO_IDX}:a:0" \
    -t "$TOTAL_DURATION" \
    -c:v libx264 -crf 12 -preset veryslow \
        -profile:v high -level 4.2 -pix_fmt yuv420p \
    -c:a aac -b:a 256k -ar 44100 \
    -movflags +faststart \
    "$OUTPUT_DIR/final_video.mp4" -y

# ---------- Verify ----------
if [ ! -f "$OUTPUT_DIR/final_video.mp4" ]; then
    echo "❌ Encoding ব্যর্থ!"
    exit 1
fi

echo "─────────────────────────────────────────────"
echo "✅ সম্পন্ন! আউটপুট:"
ls -lh "$OUTPUT_DIR/final_video.mp4"
echo ""
echo "📊 Output info:"
ffprobe -v error -show_entries format=duration,size,bit_rate \
    -of default=noprint_wrappers=1 "$OUTPUT_DIR/final_video.mp4"