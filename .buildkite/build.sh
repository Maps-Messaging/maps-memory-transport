#!/usr/bin/env bash
set -e

echo "--- Select configured JDK 25"
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
if [ ! -x "${JAVA_HOME}/bin/javac" ]; then
  echo "Configured JDK 25 compiler is missing: ${JAVA_HOME}/bin/javac" >&2
  exit 1
fi
export PATH="${JAVA_HOME}/bin:${PATH}"
hash -r
"${JAVA_HOME}/bin/java" -version
"${JAVA_HOME}/bin/javac" -version
test "$("${JAVA_HOME}/bin/javac" -version 2>&1 | cut -d ' ' -f 2 | cut -d . -f 1)" = 25
mvn -version
SONAR_TOKEN=$(buildkite-agent secret get SONAR_TOKEN)
mvn clean deploy
mvn sonar:sonar -Dsonar.token="${SONAR_TOKEN}" -Dsonar.branch.name=development
