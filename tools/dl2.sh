#!/bin/bash
# Download prefix region [0, PRE) as piece_p, chunked + kill-safe. Existing piece_0..7 kept.
URL="https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip"
PRE=672394240
CHUNK=$(( 8 * 1024 * 1024 ))
export URL PRE CHUNK

s=P
start=0
end=$((PRE - 1))
need=$PRE
cur=$(stat -c %s "piece_$s" 2>/dev/null || echo 0)
while [ "$cur" -lt "$need" ]; do
  off=$(( start + cur ))
  want=$(( off + CHUNK - 1 ))
  [ "$want" -gt "$end" ] && want=$end
  if curl -sS --max-time 300 --retry 6 --retry-delay 3 --speed-limit 1024 --speed-time 60 -r "$off-$want" -o "tmp_$s" "$URL" 2> "err_$s.log"; then
    cat "tmp_$s" >> "piece_$s"
    rm -f "tmp_$s"
    cur=$(stat -c %s "piece_$s")
    echo "prefix progress: $cur / $need"
  else
    echo "prefix: curl error at $off, retrying"
    sleep 3
  fi
done
echo "=== PREFIX DONE: $(stat -c %s piece_p 2>/dev/null) bytes ==="
