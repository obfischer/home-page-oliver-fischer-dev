#!/usr/bin/env bash

if ! which hugo >/dev/null 2>&1; then
  echo "🤯 hugo not found, will abort"
  exit 1
fi

echo "$(pwd)"

set -x
hugo server $@
