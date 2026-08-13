#!/usr/bin/env bash
set -euo pipefail

DEB_DIR=${1:?Usage: patch-deb-dependencies.sh <deb-directory>}

if ! command -v dpkg-deb >/dev/null 2>&1; then
  echo "dpkg-deb is required to patch Linux package metadata" >&2
  exit 1
fi

shopt -s nullglob
packages=("$DEB_DIR"/*.deb)
if (( ${#packages[@]} == 0 )); then
  echo "No Debian package found in $DEB_DIR; skipping metadata patch"
  exit 0
fi

# packageDeb normally emits one file. Pick the newest if an old version is also present.
deb_file=${packages[0]}
for candidate in "${packages[@]}"; do
  if [[ "$candidate" -nt "$deb_file" ]]; then
    deb_file=$candidate
  fi
done
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/cloudstream-deb.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT

dpkg-deb --raw-extract "$deb_file" "$work_dir/package"
control_file="$work_dir/package/DEBIAN/control"
if [[ ! -f "$control_file" ]]; then
  echo "Missing DEBIAN/control in $deb_file" >&2
  exit 1
fi

# JavaFX native libraries are embedded inside JARs, so jpackage does not discover
# their GTK and ALSA dependencies. Add alternatives for pre/post-t64 distributions.
awk '
  BEGIN { found = 0 }
  /^Depends:[[:space:]]*/ {
    found = 1
    line = $0
    if (line !~ /libgtk-3-0/) line = line ", libgtk-3-0t64 | libgtk-3-0"
    if (line !~ /libasound2/) line = line ", libasound2t64 | libasound2"
    if (line ~ /libpng16-16t64/ && line !~ /libpng16-16t64[^,]*\|[[:space:]]*libpng16-16/) {
      sub(/libpng16-16t64/, "libpng16-16t64 | libpng16-16", line)
    } else if (line ~ /libpng16-16([,[:space:]]|$)/ && line !~ /libpng16-16t64/) {
      sub(/libpng16-16/, "libpng16-16t64 | libpng16-16", line)
    }
    print line
    next
  }
  { print }
  END {
    if (!found) print "Depends: libgtk-3-0t64 | libgtk-3-0, libasound2t64 | libasound2"
  }
' "$control_file" > "$control_file.new"
mv "$control_file.new" "$control_file"

# Match the AWT window class so Linux docks group the running window with its launcher.
while IFS= read -r -d '' desktop_file; do
  if grep -q '^StartupWMClass=' "$desktop_file"; then
    sed -i 's/^StartupWMClass=.*/StartupWMClass=com-cloudstream-desktop-MainKt/' "$desktop_file"
  else
    sed -i '/^\[Desktop Entry\]/a StartupWMClass=com-cloudstream-desktop-MainKt' "$desktop_file"
  fi
done < <(find "$work_dir/package" -type f -name '*.desktop' -print0)

dpkg-deb -Zxz --build --root-owner-group "$work_dir/package" "$deb_file" >/dev/null
echo "Patched $(basename "$deb_file") with JavaFX runtime dependencies"
