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

# One subdirectory of __screenshots__/ per spec file (e.g.
# spec-app-lines-line-detail-line-detail.component.spec.js/), each holding that spec's own
# page.screenshot() output -- map each to the doc image name index.adoc expects.
screenshots_dir="$(find "$ui_dir/dist/test-out" -type d -name '__screenshots__')"
copy_screenshot() {
  local spec_dir_glob="$1" dest_name="$2"
  # shellcheck disable=SC2086 # glob must expand unquoted to match the single spec directory
  cp "$(find "$screenshots_dir" -type d -name $spec_dir_glob)"/*.png "$images_dir/$dest_name"
}
copy_screenshot '*-line-detail.component.spec.js' 'line-detail.png'
copy_screenshot '*-edit-line-dialog.component.spec.js' 'move-dialog.png'
