#!/bin/bash
URL="https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip"
PRE=672394240
TOTAL=1738815903
SLICE=$(( (TOTAL - PRE + 7) / 8 ))
CHUNK=$(( 8 * 1024 * 1024 ))
export URL PRE TOTAL SLICE CHUNK

for s in $(seq 0 7); do
  (
    start=$(( PRE + s * SLICE ))
    end=$(( start + SLICE - 1 ))
    [ "$end" -ge "$TOTAL" ] && end=$((TOTAL - 1))
    need=$(( end - start + 1 ))
    cur=$(stat -c %s "piece_$s" 2>/dev/null || echo 0)
    while [ "$cur" -lt "$need" ]; do
      off=$(( start + cur ))
      want=$(( off + CHUNK - 1 ))
      [ "$want" -gt "$end" ] && want=$end
      if curl -sS --max-time 300 --retry 6 --retry-delay 3 --speed-limit 1024 --speed-time 60 -r "$off-$want" -o "tmp_$s" "$URL" 2> "err_$s.log"; then
        cat "tmp_$s" >> "piece_$s"
        rm -f "tmp_$s"
        cur=$(stat -c %s "piece_$s")
      else
        echo "slice $s: curl error at $off, retrying"
        sleep 3
      fi
    done
    echo "slice $s done: $(stat -c %s piece_$s 2>/dev/null)/$need"
  ) &
done
wait
echo "=== ALL SLICES FINISHED ==="
du -sb piece_* 2>/dev/null | awk '{s+=$1} END {printf "pieces total: %.1f MB\n", s/1048576}'
