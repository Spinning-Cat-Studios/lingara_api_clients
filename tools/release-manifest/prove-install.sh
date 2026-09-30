#!/bin/sh
# prove-install.sh <id> <tag> [--deadline 15m] (ADR 29.9.26v D8)
#
# Runs the install line the site shows (`release-manifest install-line`, the
# site's own substitution) against the registry it names, in an empty scratch
# project outside the checkout, so nothing resolves from the working tree.
# Registries propagate at different speeds, so it retries every 60 seconds
# until its deadline, then exits 1 with `install proof failed: <id> <line>`.
#
# Every attempt starts cold: a fresh scratch directory holding every tool's
# cache, because each tool remembers a failed lookup.
set -eu

usage() { echo "usage: prove-install.sh <id> <tag> [--deadline <N>m|<N>s]" >&2; exit 2; }

id=${1:-}
tag=${2:-}
[ -n "$id" ] && [ -n "$tag" ] || usage
shift 2
deadline=15m
while [ $# -gt 0 ]; do
    case "$1" in
        --deadline) [ $# -ge 2 ] || usage; deadline=$2; shift 2 ;;
        *) usage ;;
    esac
done
case "$deadline" in
    *m) seconds=$(( ${deadline%m} * 60 )) ;;
    *s) seconds=${deadline%s} ;;
    *) seconds=$deadline ;;
esac

checkout=$(pwd)
if command -v release-manifest >/dev/null 2>&1; then
    line=$(release-manifest install-line "$id" "$tag")
else
    line=$(cargo run -q -p release-manifest -- install-line "$id" "$tag")
fi

# One attempt in scratch directory $1. It runs as an `if` condition, where
# `set -e` does not apply, so every step is chained with `&&`. The line itself
# runs under `sh -e`, so a two-line snippet stops at its first failure.
attempt() {
    scratch=$1
    cd "$scratch" || return 1
    case "$id" in
        typescript)
            npm init -y >/dev/null &&
            npm_config_cache="$scratch/npm" npm_config_prefer_online=true sh -ec "$line" &&
            node -e "import('@lingara/api')" ;;
        rust)
            cargo new --quiet proof && cd proof &&
            CARGO_HOME="$scratch/cargo" sh -ec "$line" &&
            CARGO_HOME="$scratch/cargo" cargo fetch ;;
        go)
            go mod init proof >/dev/null 2>&1 &&
            GOMODCACHE="$scratch/gomod" GOFLAGS=-modcacherw sh -ec "$line" ;;
        ruby)
            GEM_HOME="$scratch/gems" GEM_PATH="$scratch/gems" sh -ec "$line" &&
            GEM_HOME="$scratch/gems" GEM_PATH="$scratch/gems" ruby -e 'require "lingara"' ;;
        php)
            composer init -n --name proof/proof >/dev/null &&
            COMPOSER_CACHE_DIR="$scratch/composer" sh -ec "$line" ;;
        java)
            { echo '<project xmlns="http://maven.apache.org/POM/4.0.0">'
              echo '  <modelVersion>4.0.0</modelVersion>'
              echo '  <groupId>proof</groupId><artifactId>proof</artifactId><version>0</version>'
              echo '  <dependencies>'; echo "$line"; echo '  </dependencies>'
              echo '</project>'; } > pom.xml &&
            mvn -q -U -Dmaven.repo.local="$scratch/m2" dependency:resolve ;;
        kotlin)
            # Only dependency metadata is cold: the runner's Gradle
            # distribution is copied in rather than downloaded again.
            : > settings.gradle.kts &&
            printf 'plugins { java }\nrepositories { mavenCentral() }\ndependencies {\n%s\n}\n' "$line" > build.gradle.kts &&
            mkdir -p "$scratch/gradle/wrapper" &&
            { [ ! -d "$HOME/.gradle/wrapper/dists" ] || cp -R "$HOME/.gradle/wrapper/dists" "$scratch/gradle/wrapper/"; } &&
            GRADLE_USER_HOME="$scratch/gradle" "$checkout/gradlew" -p "$scratch" dependencies \
                --configuration runtimeClasspath --refresh-dependencies > "$scratch/deps.txt" 2>&1 &&
            ! grep -q FAILED "$scratch/deps.txt" ;;
        *)
            echo "prove-install.sh: no proof for $id" >&2; exit 2 ;;
    esac
}

end=$(( $(date +%s) + seconds ))
while :; do
    scratch=$(mktemp -d "${TMPDIR:-/tmp}/prove-install.XXXXXX")
    if (attempt "$scratch"); then
        rm -rf "$scratch"
        echo "✓ install proof: $id $line"
        exit 0
    fi
    rm -rf "$scratch"
    if [ $(( $(date +%s) + 60 )) -gt "$end" ]; then
        echo "install proof failed: $id $line" >&2
        exit 1
    fi
    echo "prove-install.sh: $id not installable yet; retrying in 60s" >&2
    sleep 60
done
