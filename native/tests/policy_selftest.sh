#!/system/bin/sh
set -eu
POLICY=${1:-/data/local/tmp/sepolicy.rule}
while IFS= read -r line; do
  normalized=$(printf '%s' "$line" | tr ':' ' ')
  [ -z "$normalized" ] || ksud sepolicy check "$normalized"
done <"$POLICY"
echo "PASS: KernelSU accepts module sepolicy rules"
