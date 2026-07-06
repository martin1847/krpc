#!/usr/bin/env bash
# BENCH-001 — build both server runtimes under test. Run from benchmark/.
# JAVA_HOME must point at a GraalVM with native-image (host-local native build;
# the fallback of a Mandrel CONTAINER build is documented in RESULTS.md §caveats).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== JVM fast-jar (server-quarkus/build/quarkus-app/quarkus-run.jar) =="
gradle :server-quarkus:build -x test --console=plain

echo "== driver classpath (driver/build/driver-classpath.txt) =="
gradle :driver:dumpClasspath --console=plain

echo "== native image (host-local, graalvm-25) =="
# SPEC §13.3: native needs -Dquarkus.package.jar.enabled=false (Gradle can't emit both).
# container-build=false => uses JAVA_HOME's native-image. FALLBACK (host toolchain
# unavailable): container-build=true + -Dquarkus.native.builder-image=<Mandrel jdk-21>;
# if you take that fallback you MUST also run the JVM group in a matching container
# (never mix native-container with JVM-host — see RESULTS.md).
gradle :server-quarkus:build \
  -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=false \
  -Dquarkus.package.jar.enabled=false \
  -x test --console=plain

echo "== done: quarkus-run.jar + *-runner + driver-classpath.txt =="
