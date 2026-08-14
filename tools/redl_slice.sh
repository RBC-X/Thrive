#!/bin/bash
# Hardened single-slice redownloader. Usage: redl_slice.sh <piece_name> <start> <end>
URL="https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip"
PN="$1"; START="$2"; END="$3"
CHUNK=$(( 8 * 1024 * 1024 ))
# resumable: keep existing piece data
need=$(( END - START + 1 ))
cur=0
while [ "$cur" -lt "$need" ]; do
  off=$(( START + cur ))
  want=$(( off + CHUNK - 1 ))
  [ "$want" -gt "$END" ] && want=$END
  wantlen=$(( want - off + 1 ))
  # no --retry: the loop is the retry. verify curl exit code AND byte count.
  curl -sS --max-time 300 -r "$off-$want" -o "tmp_$PN" "$URL"
  rc=$?
  got=$(stat -c %s "tmp_$PN" 2>/dev/null || echo 0)
  if [ "$rc" -eq 0 ] && [ "$got" -eq "$wantlen" ]; then
    cat "tmp_$PN" >> "piece_$PN"
    rm -f "tmp_$PN"
    cur=$(( cur + wantlen ))
    echo "$PN: $cur / $need"
  else
    echo "$PN: failed rc=$rc got=$got want=$wantlen at $off, retrying"
    rm -f "tmp_$PN"
    sleep 2
  fi
done
echo "$PN DONE: $(stat -c %s piece_$PN) bytes"
