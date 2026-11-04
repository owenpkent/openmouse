#!/bin/sh
# Verifies a release tag the way anyone reading this repository should before
# trusting it, and the way scripts/publish-release.ps1 does before building:
#
# - the tag is named vX.Y.Z,
# - it is an annotated tag whose object is named for the tag and carries a
#   message (the message is the release notes),
# - it carries exactly one SSH signature, from a key in release-signers at the
#   repository root. `git verify-tag` is pointed at that file alone, so a key in
#   the owner's own git configuration does not count.
#
# Usage: scripts/verify-release-tag.sh v0.2.0
# Exit 0 when the tag verifies, 1 when it does not (with the reason), 2 on usage.
set -eu

tag=${1:-}
case "$tag" in
  v[0-9]*.[0-9]*.[0-9]*) ;;
  *) echo "usage: $0 vX.Y.Z" >&2; exit 2 ;;
esac

cd "$(git rev-parse --show-toplevel)"

if ! type=$(git cat-file -t "$tag" 2>/dev/null); then
  echo "$tag: no such tag" >&2
  exit 1
fi
if [ "$type" != tag ]; then
  echo "$tag: not an annotated tag (a lightweight tag cannot carry a signature)" >&2
  exit 1
fi

object=$(git cat-file tag "$tag")
named=$(printf '%s\n' "$object" | sed -n 's/^tag //p' | head -n 1)
if [ "$named" != "$tag" ]; then
  echo "$tag: the tag object is named '$named', not '$tag'" >&2
  exit 1
fi

subject=$(git tag -l --format='%(contents:subject)' "$tag")
if [ -z "$subject" ]; then
  echo "$tag: empty message; the tag message is the release notes" >&2
  exit 1
fi

sigs=$(printf '%s\n' "$object" | grep -c '^-----BEGIN SSH SIGNATURE-----$' || true)
if [ "$sigs" -ne 1 ]; then
  echo "$tag: expected exactly one SSH signature, found $sigs" >&2
  exit 1
fi

if ! git -c gpg.ssh.allowedSignersFile=release-signers verify-tag "$tag" 2>/dev/null; then
  echo "$tag: the signature does not verify against release-signers" >&2
  git -c gpg.ssh.allowedSignersFile=release-signers verify-tag "$tag" 2>&1 | sed 's/^/  /' >&2 || true
  exit 1
fi

echo "$tag verifies against release-signers: $subject"
