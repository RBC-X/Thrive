#!/bin/bash
# Safe resumable downloader for slices 1-7.
# - taskkill strays first, confirm none survive
# - pieces are only ever extended with byte-verified chunks
# - on resume: overshoots truncate to length; partial tails align to
#   chunk boundary; sizes are multiples of CHUNK => verified prefix
set -u
URL="https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip"
TOTAL=1738815903
PRE=672394240
SLICE=133302708
CHUNK=2097152

for i in 1 2 3; do taskkill //F //T //IM curl.exe >/dev/null 2>&1; sleep 1; done
sleep 1
if tasklist 2>/dev/null | grep -qi curl; then
  echo "WARNING: curls still alive after taskkill"
fi

trunc() { # trunc <file> <bytes>
  python - "$1" "$2" <<'PYEOF'
import sys
f = open(sys.argv[1], 'r+b')
f.truncate(int(sys.argv[2]))
f.close()
PYEOF
}

dl_slice() {
  local s="$1"
  local start=$((PRE + SLICE * s))
  local end=$((PRE + SLICE * (s + 1) - 1))
  [ "$end" -gt "$TOTAL" ] && end=$TOTAL
  local length=$((end - start + 1))
  local piece="piece_$s" tmp="tmp_$s"
  local pos=0

  if [ -f "$piece" ]; then
    pos=$(stat -c %s "$piece" 2>/dev/null || echo 0)
    if [ "$pos" -ge "$length" ]; then
      # overshoot or complete: trim to exact length
      if [ "$pos" -gt "$length" ]; then trunc "$piece" "$length" || { pos=0; : > "$piece"; }; fi
      pos=$length
    else
      local rem=$((pos % CHUNK))
      if [ "$rem" -ne 0 ]; then
        local cut=$((pos - rem))
        trunc "$piece" "$cut" || { pos=0; : > "$piece"; pos=0; }
        pos=$cut
      fi
    fi
  fi

  while [ "$pos" -lt "$length" ]; do
    local lo=$((start + pos))
    local hi=$((start + pos + CHUNK - 1))
    [ "$hi" -gt "$end" ] && hi=$end
    local want=$((hi - lo + 1))
    rm -f "$tmp"
    curl -sf --connect-timeout 15 --max-time 300 -r "$lo-$hi" "$URL" -o "$tmp" 2>/dev/null
    local got=0
    [ -f "$tmp" ] && got=$(stat -c %s "$tmp" 2>/dev/null || echo 0)
    if [ "$got" -eq "$want" ]; then
      cat "$tmp" >> "$piece"
      pos=$((pos + got))
    else
      rm -f "$tmp"
      sleep 1
    fi
  done
  rm -f "$tmp"
  echo "slice $s complete"
}

for s in 1 2 3 4 5 6 7; do dl_slice "$s" & done
wait
echo "ALL SLICES COMPLETE"
