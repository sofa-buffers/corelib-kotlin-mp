---
name: release
description: Cut a release of corelib-kotlin-mp — pick the version, bump every place that states it, land that through a PR, then tag vX.Y.Z and publish the GitHub Release. Use when the user asks to release, tag, or bump the version of this library.
argument-hint: "[X.Y.Z]"
---

# Release corelib-kotlin-mp

This follows the convention every SofaBuffers corelib uses (corelib-java, -rs, -ts,
-c-cpp, …):

- **The git tag `vX.Y.Z` is the source of truth.** Every file that states the
  version must already say `X.Y.Z` on the commit the tag points at.
- **Tag format: a lowercase `v` followed by the semver version**, e.g. `v1.2.3`.
  Never `1.2.3`, `V1.2.3` or `release-1.2.3`. The files themselves carry the bare
  `X.Y.Z`, without the `v`.
- **Pre-1.0 semver:** a *minor* bump may break the API or the wire output; a *patch*
  bump must not.
- **The family moves together.** The other corelibs release as a group under one
  version (they were all tagged `v0.10.0` on 2026-08-01). A family-wide release puts
  this repo on that same number.
- The version bump lands on `main` **through a PR** on a `release/vX.Y.Z` branch,
  with the commit `chore(release): X.Y.Z`. The tag goes on after that. The repo only
  allows **rebase merges**.

Argument: `$ARGUMENTS` is the version to release (`X.Y.Z`, without the `v`). It may
be empty.

Tagging, pushing and publishing are visible to others, and a pushed tag gets
cached. **Stop and confirm with the user** before step 5 (open the PR), step 6
(merge it) and step 7 (push the tag and publish the release).

## 1. Preconditions

```bash
git checkout main && git pull -p
git status --short                      # must be empty
git tag --sort=-v:refname | head -3     # newest release, if there is one
```

- CI must be green on `main`'s HEAD:
  `gh run list --workflow ci.yml --commit "$(git rev-parse HEAD)" --json conclusion,status`
- The shared conformance corpus must be the upstream copy. The `Shared vectors`
  workflow only runs daily and on PRs that touch the file, so check it here:
  ```bash
  curl -fsSL https://raw.githubusercontent.com/sofa-buffers/corelib-c-cpp/main/assets/test_vectors.json \
    | sha256sum; sha256sum assets/test_vectors.json
  ```
  If the two differ, refresh the file in a PR of its own before releasing. Do not
  release on a stale corpus.

## 2. Choose the version

Collect this and show it to the user:

```bash
LAST=$(git describe --tags --abbrev=0 2>/dev/null || true)
git log --oneline ${LAST:+$LAST..}HEAD          # everything the release carries
git log --format=%s ${LAST:+$LAST..}HEAD | grep -E '^[a-z]+(\([^)]*\))?!:'   # breaking changes
for r in corelib-java corelib-rs corelib-ts corelib-c-cpp corelib-go corelib-zig corelib-dart; do
  printf '%-15s ' $r; gh release list -R sofa-buffers/$r -L 1 --json tagName --jq '.[0].tagName'
done
```

- If `$ARGUMENTS` names a version, use it. It must be valid semver and greater than
  `LAST`.
- If it is empty, propose one. If any commit is marked breaking (`!`), bump the
  minor; otherwise bump the patch. If the family has moved ahead of this repo, the
  family's version wins. **The first release (no tag yet) has no default. Ask the
  user.** Joining the family's current number is the likely answer.
- `Sofab.API_VERSION` is the **wire contract** and stays `1`. It does not follow the
  release version, so never change it here.

## 3. Bump every place that states the version

Only two places state it. Nothing tests the README one, and corelib-java shipped
v0.10.0 with a stale README for exactly that reason. Update both in the same commit:

| File | What to change |
|---|---|
| `build.gradle.kts` | `version = "X.Y.Z"` (the Gradle project version; the Maven publications and Dokka take it from here) |
| `README.md` | the coordinate in "Targets and coordinates": `implementation("org.sofabuffers:corelib-kotlin-mp:X.Y.Z")` |

Then look for anything that still carries the old number:

```bash
git grep -nF "$OLD" -- ':!assets/test_vectors.json'
```

Every match left over is either a place to add to this table (update the table in
this skill too) or something unrelated, such as a dependency version. Plugin,
dependency and toolchain versions are **not** part of a release. Renovate handles
them.

While `README.md` is open, check what CORELIB_PLAN §9 requires: every version,
command and API name it states must match the code as it stands.

## 4. Verify locally

```bash
./gradlew build koverVerify --stacktrace
./gradlew publishToMavenLocal && ls ~/.m2/repository/org/sofabuffers/corelib-kotlin-mp/
```

The second command proves the artifacts come out under the new version for every
target (`corelib-kotlin-mp`, `-jvm`, `-js`, `-linuxx64`, `-linuxarm64`). A cold
Kotlin/Native toolchain makes the first run slow, so run it in the background if it
has to download.

## 5. Release commit and PR (confirm first)

```bash
git switch -c release/vX.Y.Z
git add build.gradle.kts README.md
git commit        # message below
git push -u origin release/vX.Y.Z
gh pr create --title "chore(release): X.Y.Z" --body "…"
```

Commit message, modelled on the family's release commits:

```
chore(release): X.Y.Z

The git tag is the source of truth for the version; this brings the Gradle
project version and the README's dependency coordinate in line with the
vX.Y.Z tag that follows.

<one paragraph: what this release carries, and whether it is breaking under the
pre-1.0 rule that a minor bump may break API or wire output>
```

End the commit and the PR body with the attribution lines the session prescribes.

## 6. Merge (confirm first)

Wait until CI is green on the PR (`gh pr checks --watch`), then:

```bash
gh pr merge --rebase
git checkout main && git pull -p
grep -n '^version' build.gradle.kts      # must say X.Y.Z
```

A rebase merge rewrites the commit SHA. Tag the commit **as it is on `main`** and
wait until the `CI` run for that exact SHA is green:
`gh run list --workflow ci.yml --commit "$(git rev-parse HEAD)"`.

## 7. Tag and GitHub Release (confirm first)

```bash
TAG=vX.Y.Z
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || { echo "bad tag: $TAG"; exit 1; }
git tag -a "$TAG" -m "$TAG" "$(git rev-parse HEAD)"
git push origin "$TAG"
gh release create "$TAG" --verify-tag --title "$TAG" --notes-file <notes.md>
```

Release notes use the family's shape (see
`gh release view v0.10.0 -R sofa-buffers/corelib-java`):

```markdown
<For a family-wide release: "Aligns this library with the rest of the SofaBuffers
family at **X.Y.Z**."> The git tag is the source of truth for the version; every
package manifest matches it.

**Breaking since vPREV** — under the pre-1.0 rule that a minor bump may break API or wire output:

- **<finding / CORELIB_PLAN §ref>** — what changed and what a consumer must do.

<Non-breaking highlights, grouped: features, fixes, performance, conformance (new
shared-vector blocks now run).>
```

Write the notes from `git log vPREV..vX.Y.Z` and the merged PRs. Say what changed
for a *consumer*; do not paste the commit list. For the first release, describe what
the library is and what it conforms to instead of a "breaking since" list.

## 8. After the release

- `git ls-remote --tags origin vX.Y.Z` and `gh release view vX.Y.Z` must show the
  tag and the release.
- **Nothing is published to a package registry.** `maven-publish` is applied, but no
  repository is configured and no workflow publishes. Tell the user plainly that the
  release is a tag plus a GitHub Release; never claim the artifact is on Maven
  Central. If `release.yml` or `version-consistency.yml` has been added since this
  skill was written, watch those runs on the tag and report how they ended.
- The `release/vX.Y.Z` branch is deleted on merge (the repo setting is on). Delete
  the local branch with `git branch -D release/vX.Y.Z` (`-D`, because rebase-merged
  commits do not count as merged).
