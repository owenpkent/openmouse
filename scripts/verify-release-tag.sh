#!/bin/sh
# Verifies a release tag the way anyone reading this repository should before
# trusting it, and the way scripts/publish-release.ps1 does before building:
#
# - the tag is named vX.Y.Z,
# - it is an annotated tag whose object is named for the tag and carries a
#   message (the message is the release notes),
# - it ends in exactly one SSH signature block, and carries no other signature
#   format,
# - that signature verifies, through the SSH backend only, against the
#   release-signers file of a trusted ref: refs/remotes/origin/main by default.
#   Never the release-signers of the checked-out tree or of the tag itself,
#   which would let a tag bring its own key. A change to release-signers
#   therefore counts only once it is merged to main (and fetched).
#
# OPENMOUSE_SIGNERS_REF overrides the trusted ref (for the first release before
# release-signers reaches main, or for tests). Give a full ref name such as
# refs/heads/main: a short name like origin/main can be shadowed by a tag.
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

# The signature git checks is the trailing block of the tag object, and git
# picks its backend from that block's marker. Pin both down so what is counted
# here is exactly what git verifies: one SSH block, at the very end, nothing but
# base64 inside it, and no OpenPGP or X.509 marker anywhere.
sigs=$(printf '%s\n' "$object" | grep -c '^-----BEGIN SSH SIGNATURE-----$' || true)
if [ "$sigs" -ne 1 ]; then
  echo "$tag: expected exactly one SSH signature, found $sigs" >&2
  exit 1
fi
if printf '%s\n' "$object" | grep -q -E '^-----BEGIN (PGP SIGNATURE|PGP MESSAGE|SIGNED MESSAGE)-----'; then
  echo "$tag: carries an OpenPGP or X.509 signature marker; only an SSH signature is accepted" >&2
  exit 1
fi
if ! printf '%s\n' "$object" | awk '
  /^-----BEGIN SSH SIGNATURE-----$/ { inside = 1; next }
  inside && /^-----END SSH SIGNATURE-----$/ { inside = 0; ended = 1; next }
  ended { bad = 1 }
  inside && !/^[A-Za-z0-9+\/=]+$/ { bad = 1 }
  END { exit (bad || !ended) }'; then
  echo "$tag: the SSH signature is not a single well-formed block at the end of the tag" >&2
  exit 1
fi

# The trust anchor: release-signers as committed on the trusted ref.
signers_ref=${OPENMOUSE_SIGNERS_REF:-refs/remotes/origin/main}
signers=$(git rev-parse --absolute-git-dir)/openmouse-release-signers.$$
trap 'rm -f "$signers"' EXIT
if ! git show "$signers_ref:release-signers" > "$signers" 2>/dev/null || [ ! -s "$signers" ]; then
  echo "$tag: no release-signers on $signers_ref, the trusted ref (git fetch origin; or set OPENMOUSE_SIGNERS_REF)" >&2
  exit 1
fi

# The SSH backend only, with the other backends disabled, so a signature can
# never be routed to gpg or gpgsm. --raw passes ssh-keygen's own verdict through.
# Only 'Good "git" signature for <principal>' means a listed key: for a key not
# in the file git falls back to a check that prints 'Good "git" signature with
# ... key' and "No principal matched", which must not count.
if output=$(git -c gpg.format=ssh -c gpg.openpgp.program=false -c gpg.x509.program=false \
    -c gpg.ssh.allowedSignersFile="$signers" verify-tag --raw "$tag" 2>&1) &&
  printf '%s\n' "$output" | grep -q '^Good "git" signature for '; then
  echo "$tag verifies against release-signers on $signers_ref: $subject"
  exit 0
fi
echo "$tag: the signature does not verify against release-signers on $signers_ref" >&2
printf '%s\n' "$output" | sed 's/^/  /' >&2
exit 1
