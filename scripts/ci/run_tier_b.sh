#!/usr/bin/env bash
# Tier B playback validation on the CI emulator.
#   run_tier_b.sh <streams.json> <results.jsonl>
# Runs StreamPlaybackValidationTest in small chunks (a crash only loses one
# chunk) and appends each measured RESULT line from logcat to results.jsonl.
set -uo pipefail
streams="$1"
out="$2"
apk="core/player/build/outputs/apk/androidTest/debug/player-debug-androidTest.apk"
runner="com.tvku.core.player.test/androidx.test.runner.AndroidJUnitRunner"
test_class="com.tvku.core.player.media.StreamPlaybackValidationTest"
chunk_size="${CHUNK_SIZE:-5}"

mkdir -p "$(dirname "$out")"
: > "$out"
adb install -r -t -g "$apk"
adb logcat -G 16M || true

chunks=$(python3 - "$streams" "$chunk_size" <<'EOF'
import base64, json, sys
items = json.load(open(sys.argv[1]))
n = int(sys.argv[2])
for i in range(0, len(items), n):
    print(base64.b64encode(json.dumps(items[i:i + n]).encode()).decode())
EOF
)
total=$(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))))" "$streams")
echo "Tier B: $total streams in chunks of $chunk_size"

i=0
for b64 in $chunks; do
  i=$((i + 1))
  adb logcat -c || true
  echo "::group::chunk $i"
  timeout 1200 adb shell am instrument -w -e class "$test_class" -e streams "$b64" "$runner" | tail -n 15
  adb logcat -d -v raw -s TVKuValidation:I | grep '^RESULT ' | sed 's/^RESULT //' | tee -a "$out"
  echo "::endgroup::"
done
echo "Tier B results: $(wc -l < "$out") of $total streams measured"
