#!/usr/bin/env bash
# Cut a release: ./scripts/release.sh 1.2.0   (or 1.3.0-rc.1 for a pre-release)
#
# 1. checks you're on a clean, up-to-date main
# 2. moves CHANGELOG.md's "Unreleased" notes under the new version
# 3. sets the version in backend/pom.xml and frontend/package.json
# 4. commits "Release vX.Y.Z" and creates the tag vX.Y.Z
# It does not push; it prints the command. Pushing the tag is what publishes the image (see docs/versioning.md).
set -euo pipefail
cd "$(dirname "$0")/.."

v="${1:-}"
if ! [[ "$v" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "usage: $0 X.Y.Z[-pre.N]   e.g. $0 1.2.0" >&2
  exit 1
fi
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "Switch to main first." >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash your changes first." >&2; exit 1; }
git rev-parse -q --verify "refs/tags/v$v" >/dev/null && { echo "Tag v$v already exists." >&2; exit 1; }
if git remote get-url origin >/dev/null 2>&1; then
  git fetch -q origin main --tags
  [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main isn't in sync with origin/main. Pull or push first." >&2; exit 1; }
fi

# The Unreleased section must say what changed.
notes=$(awk '/^## \[Unreleased\]/{f=1;next} f&&/^## \[/{exit} f' CHANGELOG.md | grep -v '^\s*$' || true)
[ -n "$notes" ] || { echo "CHANGELOG.md has nothing under ## [Unreleased]. Describe the changes first." >&2; exit 1; }

today=$(date +%Y-%m-%d)
perl -0pi -e "s/^## \[Unreleased\]\n/## [Unreleased]\n\n## [$v] - $today\n/m" CHANGELOG.md
# The project's own <version> is the one right after <artifactId>hisaab</artifactId>.
perl -0pi -e "s|(<artifactId>hisaab</artifactId>\s*<version>)[^<]+(</version>)|\${1}$v\${2}|" backend/pom.xml
(cd frontend && npm version "$v" --no-git-tag-version --allow-same-version >/dev/null)

git add CHANGELOG.md backend/pom.xml frontend/package.json frontend/package-lock.json
git commit -q -m "Release v$v"
git tag -a "v$v" -m "v$v"
echo "Tagged v$v. Publish it with:"
echo "  git push origin main v$v"
