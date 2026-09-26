#!/usr/bin/env bash
# ============================================================
# Two-Part Video Recreator
# ------------------------------------------------------------
# ✔ video.mp4 এর audio বাদ
# ✔ audio.mp3 এর প্রথম 10s → video.mp4 (speed/slow করে exact 10s)
# ✔ audio.mp3 এর বাকি অংশ → endvideo.mp4
#     - endvideo বড় হলে → trim
#     - endvideo ছোট হলে → loop
# ✔ overly/photo.png পুরো ভিডিওতে overlay
# ✔ Final output: 최고 quality (CRF 12, veryslow, yuv420p)
# ============================================================

set -euo pipefail

# ---------- কনফিগ ----------
WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
VIDEO_INPUT="$WORKSPACE/video/video.mp4"
ENDVIDEO_INPUT="$WORKSPACE/video/endvideo.mp4"
AUDIO_INPUT="$WORKSPACE/audio/audio.mp3"
OVERLAY_PATH="$WORKSPACE/overly/photo.png"
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

# ---------- resolution + fps (video.mp4 থেকে) ----------
W=$(ffprobe -v 0 -of csv=p=0 -select_streams v:0 -show_entries stream=width  "$VIDEO_INPUT" || echo "")
H=$(ffprobe -v 0 -of csv=p=0 -select_streams v:0 -show_entries stream=height "$VIDEO_INPUT" || echo "")
[ -z "$W" ] || [ "$W" = "0" ] && W=1920
[ -z "$H" ] || [ "$H" = "0" ] && H=1080
W=$(( W - W % 2 ))
H=$(( H - H % 2 ))

FPS=$(ffprobe -v 0 -of csv=p=0 -select_streams v:0 \
    -show_entries stream=r_frame_rate "$VIDEO_INPUT" 2>/dev/null \
    | awk -F'/' '{ if ($2) printf "%.6f", $1/$2; else print $1 }')
[ -z "$FPS" ] || [ "$FPS" = "0" ] && FPS=30

echo "─────────────────────────────────────────────"
echo "📐 Resolution : ${W}x${H}"
echo "🎞️  FPS        : $FPS"
echo "⏱️  Durations  : video=${VIDEO_DURATION}s | endvideo=${ENDVIDEO_DURATION}s | audio=${AUDIO_DURATION}s"
echo "─────────────────────────────────────────────"

# ---------- হিসাব ----------
# প্রথম অংশের দৈর্ঘ্য = min(10, audio_duration)
FIRST_SEG_DURATION=$(awk -v a="$AUDIO_DURATION" -v t="$FIRST_SEG_TARGET" \
    'BEGIN { print (a < t) ? a : t }')

# video.mp4 কে FIRST_SEG_DURATION এ আনতে হবে
# setpts=PTS*ratio → new_dur = source_dur * ratio
# আমরা চাই new_dur = FIRST_SEG_DURATION
# তাই ratio = FIRST_SEG_DURATION / VIDEO_DURATION
PTS_RATIO=$(awk -v t="$FIRST_SEG_DURATION" -v v="$VIDEO_DURATION" \
    'BEGIN { printf "%.8f", t/v }')

# বাকি অডিওর দৈর্ঘ্য
REMAIN_AUDIO=$(awk -v a="$AUDIO_DURATION" -v f="$FIRST_SEG_DURATION" \
    'BEGIN { r = a - f; if (r < 0) r = 0; printf "%.6f", r }')

TOTAL_DURATION=$(awk -v f="$FIRST_SEG_DURATION" -v r="$REMAIN_AUDIO" \
    'BEGIN { printf "%.6f", f + r }')

echo "🎯 First segment   : ${FIRST_SEG_DURATION}s (PTS ratio: $PTS_RATIO)"
echo "🎯 Remaining audio : ${REMAIN_AUDIO}s"
echo "🎯 Total duration  : ${TOTAL_DURATION}s"
echo "─────────────────────────────────────────────"

# ---------- Overlay availability ----------
HAVE_OVERLAY=0
[ -f "$OVERLAY_PATH" ] && HAVE_OVERLAY=1

# ---------- Filter graph নির্মাণ ----------
SCALE_PAD="scale=$W:$H:force_original_aspect_ratio=decrease,pad=$W:$H:(ow-iw)/2:(oh-ih)/2,setsar=1"

# ইনপুট #0 = video.mp4 → setpts দিয়ে speed/slow
FG="[0:v]setpts=PTS*${PTS_RATIO},fps=${FPS},${SCALE_PAD}[v0];"

# ইনপুট #1 = endvideo.mp4 (stream_loop দিয়ে লুপ করা) → trim করে REMAIN_AUDIO
FG="${FG}[1:v]fps=${FPS},${SCALE_PAD},trim=end=${REMAIN_AUDIO},setpts=PTS-STARTPTS[v1];"

# concat
FG="${FG}[v0][v1]concat=n=2:v=1:a=0[vc];"

# overlay (থাকলে)
if [ "$HAVE_OVERLAY" -eq 1 ]; then
    FG="${FG}[2:v]scale=${W}:${H}:flags=lanczos,format=rgba[ovr];"
    FG="${FG}[vc][ovr]overlay=0:0:format=auto,format=yuv420p[out]"
    echo "🖼️  Overlay: ব্যবহার হবে"
else
    FG="${FG}[vc]format=yuv420p[out]"
    echo "ℹ️  Overlay: নেই – স্কিপ"
fi

# ---------- Inputs সাজানো ----------
INPUTS=(
    -i "$VIDEO_INPUT"
    -stream_loop -1 -i "$ENDVIDEO_INPUT"
)
if [ "$HAVE_OVERLAY" -eq 1 ]; then
    INPUTS+=( -loop 1 -i "$OVERLAY_PATH" )
fi
INPUTS+=( -i "$AUDIO_INPUT" )

# অডিও input index বের করা
if [ "$HAVE_OVERLAY" -eq 1 ]; then
    AUDIO_IDX=3
else
    AUDIO_IDX=2
fi

# ---------- Final encode (Single-pass) ----------
echo "🎬 Encoding final video (CRF 12, veryslow, yuv420p, high profile)..."
ffmpeg -hide_banner -loglevel warning -stats \
    "${INPUTS[@]}" \
    -filter_complex "$FG" \
    -map "[out]" -map "${AUDIO_IDX}:a:0" \
    -t "$TOTAL_DURATION" \
    -c:v libx264 -crf 12 -preset veryslow \
        -profile:v high -level 4.2 -pix_fmt yuv420p \
    -r "$FPS" \
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