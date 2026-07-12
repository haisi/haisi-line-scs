#!/usr/bin/env bash
# Runs optimistic-locking-ui's real component test suite non-interactively (Vitest Browser Mode,
# see angular.json's "test" target) and lifts whatever screenshot(s) it produced into
# generated-docs.directory/images for index.adoc to embed. No mocking server, no separate script --
# line-detail.component.spec.ts (etc.) *is* the screenshot: TestBed provider overrides fill the
# component with fake data, the test asserts on it, then calls page.screenshot() as a side effect.
# Invoked by exec-maven-plugin, bound to prepare-package -- see that plugin's comment in pom.xml.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ui_dir="$script_dir/../optimistic-locking-ui"
images_dir="$script_dir/target/generated-docs/images"

# Vitest Browser Mode writes screenshots under a fresh timestamped dist/test-out/<run-id>/ each
# run (page.screenshot()'s path resolves against that ephemeral bundle output, not the source tree
# -- passing an explicit ../-relative path here instead hits Vite's server.fs.strict sandbox), so
# clear stale runs first to make the find below unambiguous.
rm -rf "$ui_dir/dist/test-out"
(cd "$ui_dir" && npm test -- --watch=false)

mkdir -p "$images_dir"
cp "$(find "$ui_dir/dist/test-out" -path '*/__screenshots__/*' -name '*.png')" "$images_dir/line-detail.png"
