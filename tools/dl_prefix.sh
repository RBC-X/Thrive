#!/bin/bash
# Re-download the prefix piece_P = source bytes [0, 672394240) with verified chunks.
set -u
URL="https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip"
PRE=672394240
CHUNK=2097152
for i in 1 2 3; do taskkill //F //T //IM curl.exe >/dev/null 2>&1; sleep 1; done
sleep 1
piece="piece_P" tmp="tmp_P"
pos=0
if [ -f "$piece" ]; then
  pos=$(stat -c %s "$piece" 2>/dev/null || echo 0)
  if [ "$pos" -gt "$PRE" ]; then pos=0; : > "$piece"
  elif [ "$pos" -gt 0 ]; then
    rem=$((pos % CHUNK))
    if [ "$rem" -ne 0 ]; then
      cut=$((pos - rem))
      python - "$piece" "$cut" <<'PYEOF'
import sys
f = open(sys.argv[1], 'r+b'); f.truncate(int(sys.argv[2])); f.close()
PYEOF
      pos=$cut
    fi
  fi
fi
while [ "$pos" -lt "$PRE" ]; do
  lo=$pos; hi=$((pos + CHUNK - 1)); [ "$hi" -ge "$PRE" ] && hi=$((PRE - 1))
  want=$((hi - lo + 1))
  rm -f "$tmp"
  curl -sf --connect-timeout 15 --max-time 300 -r "$lo-$hi" "$URL" -o "$tmp" 2>/dev/null
  got=0; [ -f "$tmp" ] && got=$(stat -c %s "$tmp" 2>/dev/null || echo 0)
  if [ "$got" -eq "$want" ]; then
    cat "$tmp" >> "$piece"; pos=$((pos + got))
  else
    rm -f "$tmp"; sleep 1
  fi
done
rm -f "$tmp"
echo "piece_P complete: $(stat -c %s "$piece") bytes"
