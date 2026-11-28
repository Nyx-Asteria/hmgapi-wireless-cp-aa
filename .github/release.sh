#!/bin/sh
# The release files for one tag: the mod signed with the author key, its NEMESIS
# packages and SHA256SUMS, in out/assets. Run by .github/workflows/release.yml; runs
# the same on a computer:
#   HMGAPI_SDK=<unpacked SDK> .github/release.sh <tag> <author key>
# The tag must be v<mod.json version>. The key must be the author key this mod has
# been configured to use (adjacent .pub file, UUID below): an update signed with
# another key doesn't install over the old one (docs/mod-repos.md in HMG-API, section 5).
set -e
MOD=wireless-carplay-aa
UUID=d515981b-afc8-8230-ae0f-62d32de366bc
REPO=https://github.com/Nyx-Asteria/hmgapi-wireless-cp-aa
TAG=${1:?usage: release.sh <tag> <author key>}; KEY=${2:?usage: release.sh <tag> <author key>}
SDK=$(cd "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked HMG-API mod SDK}" && pwd)
cd "$(dirname "$0")/.."
case $KEY in /*) ;; *) KEY=$PWD/$KEY ;; esac
V=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$MOD/mod.json")
[ "$TAG" = "v$V" ] || { echo "tag $TAG, but $MOD/mod.json's version is $V: tag v$V"; exit 1; }
# Use the fork author's public key supplied alongside the private key.
ENG="java -cp $SDK/lib/hmgapi-engine.jar hmgapi.engine.Signing"
test -s "$KEY" && test -s "$KEY.pub" || { echo "Both author key files are required"; exit 1; }
p=$($ENG uuid "$KEY.pub")
[ "$p" = "$UUID" ] || { echo "Wrong author UUID: $p, wanted $UUID"; exit 1; }
rm -rf out/build out/work out/packages out/dist out/assets
sh "$MOD/native/identity/build.sh"
sh "$SDK/bin/build-mod.sh" "$MOD" "out/build/$MOD" "out/work/$MOD"
Z=$(HMGAPI_MOD_BUILD="$PWD/out/build/$MOD" sh "$SDK/bin/mkmodpkg.sh" "$MOD" "$KEY" | tail -1)
[ "$(basename "$Z")" = "$MOD-$V.zip" ] || { echo "$Z"; echo "signing failed: is the key (MOD_KEY) the private half of the supplied public key, author $UUID?"; exit 1; }
# the signature must check out with the author's UUID, as the engine checks it on the car
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
unzip -q "$Z" -d "$W"
$ENG verify "$W" "$UUID"
packaged_native=$(find "$W" -type f -name libcarplayidentity.so)
test -n "$packaged_native"
cmp "$MOD/files/libcarplayidentity.so" "$packaged_native"
sh "$SDK/bin/mknemesismod.sh" "$Z" out/dist
mkdir -p out/assets
cp "$Z" out/dist/"$MOD-$V"-nemesis.zip out/dist/"$MOD-$V"-nemesis-package.zip out/assets/
(cd out/assets && sha256sum *.zip > SHA256SUMS && cat SHA256SUMS)
{
    printf 'source_commit=%s\n' "$(git rev-parse HEAD)"
    printf 'author_uuid=%s\n' "$UUID"
    sha256sum "$MOD/files/libcarplayidentity.so"
} > out/assets/BUILD-PROVENANCE.txt
cp ".github/release-notes/$TAG.md" out/assets/CHANGELOG.md
# for the catalogue PR in HMG-API, once the release is published (a maintainer's); the
# catalogue lists finals only, so for a pre-release the line a HMG-API pre-release pins it
# with (tools/release-mods.txt)
D=$REPO/releases/download/$TAG/$MOD-$V.zip
case $V in
    *-*) echo "$MOD $V $D $(sha256sum < "$Z" | cut -d' ' -f1)" > out/catalog-command.txt
         echo "Pre-release: not for the catalogue. tools/release-mods.txt line, for a HMG-API pre-release:" ;;
    *) printf '%s\n' "curl -fLO $D" "tools/catalog.py add $MOD-$V.zip --download $D --repo $REPO" > out/catalog-command.txt
       echo "Catalogue (in HMG-API, after publishing):" ;;
esac
cat out/catalog-command.txt
