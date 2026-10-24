#!/usr/bin/env bash
# Turns a fresh copy of the template into a service for one bounded context.
# Usage: scripts/init-service.sh <context>      e.g. scripts/init-service.sh catalog
# Only renames (package, project, image); it never removes features.
set -euo pipefail

context="${1:?usage: $0 <context>}"
if [[ ! "$context" =~ ^[a-z][a-z0-9]*$ ]]; then
    echo "context must be lowercase letters and digits, e.g. catalog" >&2
    exit 1
fi

cd "$(dirname "$0")/.."

# Move package directories: .../nothingeats/example -> .../nothingeats/<context>
find . -type d -path '*/kotlin/io/github/slashncode/nothingeats/example' -not -path '*/build/*' | while read -r dir; do
    mv "$dir" "$(dirname "$dir")/$context"
done

# Rename identifiers in sources, build files and docs.
grep -rlE 'nothingeats[.-]example' . \
    --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=build --exclude-dir=.kotlin --exclude=init-service.sh |
    while read -r file; do
        perl -pi -e "s/nothingeats\.example/nothingeats.$context/g; s/nothingeats-example-service/nothingeats-$context-service/g; s#/api/example/#/api/$context/#g" "$file"
    done

echo "Initialised nothingeats-$context-service. Next: replace the Item example with the real domain."
