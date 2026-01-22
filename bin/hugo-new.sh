#!/usr/bin/env bash

set -eu

if ! brew --prefix icu4c >/dev/null 2>&1; then
  echo "🤯 Homebrew bottle icu4c not found, will abort"
  exit 1
fi

export PATH="$(brew --prefix icu4c)/bin:$PATH"

if ! which uconv >/dev/null 2>&1; then
  echo "🤯 uconv not found, will abort"
  exit 1
fi


if ! which hugo >/dev/null 2>&1; then
  echo "🤯 hugo not found, will abort"
  exit 1
fi

if ! which iconv >/dev/null 2>&1; then
  echo "🤯 iconv not found, will abort"
  exit 1
fi

read -t 90 -p "Titel des Contents: " title || exit 1

title_lower_case="${title,,}"
title_without_ws="${title_lower_case// /-}"
title_complete=$(echo ${title_without_ws} | uconv -x "de-ASCII; Latin-ASCII")

COMPUTED_PATH="content/posts/$(date "+%Y")/${title_complete}"

set -x

HUGO_CONTENT_TITLE=${title} \
  hugo new content \
    ${COMPUTED_PATH}/index.adoc
