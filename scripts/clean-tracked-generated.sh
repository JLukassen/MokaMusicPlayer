#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

echo "Removing generated/local Android files from the Git index (files remain ignored locally where appropriate)..."
for path in .idea app/.cxx local.properties; do
  if git ls-files --error-unmatch "$path" >/dev/null 2>&1 || git ls-files "$path" | grep -q .; then
    git rm -r --cached --ignore-unmatch "$path"
  fi
done

echo
echo "Review with: git status"
echo "Then commit the cleanup. .gitignore already excludes these paths."
