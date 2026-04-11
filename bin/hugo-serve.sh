#!/usr/bin/env bash

if ! which hugo >/dev/null 2>&1; then
  echo "🤯 hugo not found, will abort"
  exit 1
fi

echo "$(pwd)"

export PATH=./node_modules/.bin/:$PATH

set -x
hugo server $@
