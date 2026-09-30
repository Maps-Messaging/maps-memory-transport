#!/usr/bin/env bash
set -e
echo "--- Java environment before JDK selection"
printf 'JAVA_HOME=%s\nPATH=%s\n' "${JAVA_HOME:-<unset>}" "$PATH"
for tool in java javac mvn; do
  command -v "$tool" || true
done
java -version 2>&1 || true
javac -version 2>&1 || true
mvn -version 2>&1 || true

echo "--- Registered Java alternatives"
if command -v update-alternatives >/dev/null 2>&1; then
  update-alternatives --list java || true
  update-alternatives --list javac || true
elif command -v alternatives >/dev/null 2>&1; then
  alternatives --display java || true
  alternatives --display javac || true
fi

echo "--- Installed Java runtimes and compilers"
for root in /usr/lib/jvm /usr/lib64/jvm /usr/java /opt /usr/local "${HOME}/.sdkman/candidates/java" "${JAVA_HOME:-/nonexistent}"; do
  [ -d "$root" ] || continue
  find -L "$root" -type f \( -path '*/bin/java' -o -path '*/bin/javac' \) -print 2>/dev/null || true
done | sort -u | while IFS= read -r executable; do
  printf '\n%s -> %s\n' "$executable" "$(readlink -f "$executable")"
  "$executable" -version 2>&1 || true
done

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
