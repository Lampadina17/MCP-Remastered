#!/bin/sh
set -eu

cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -n "${JAVA_HOME:-}" ]; then
    if [ ! -x "$JAVA_HOME/bin/java" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
        printf '%s\n' 'ERROR: JAVA_HOME must point to a JDK 25 or newer.' >&2
        exit 1
    fi
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
fi
if ! command -v java >/dev/null 2>&1 || ! command -v javac >/dev/null 2>&1; then
    printf '%s\n' 'ERROR: Install JDK 25+ and add its bin directory to PATH.' >&2
    exit 1
fi
if ! java_version=$(java --version 2>/dev/null); then
    printf '%s\n' 'ERROR: Setup requires Java 25+. Check JAVA_HOME and PATH.' >&2
    exit 1
fi
major=$(printf '%s\n' "$java_version" | sed -n '1s/^[^ ][^ ]* \([0-9][0-9]*\).*$/\1/p')
case "$major" in
    ''|*[!0-9]*) printf '%s\n' 'ERROR: Cannot determine Java version.' >&2; exit 1 ;;
esac
if [ "$major" -lt 25 ]; then
    printf '%s\n' 'ERROR: Setup requires Java 25+. Check JAVA_HOME and PATH.' >&2
    exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
    printf '%s\n' 'ERROR: Install Maven and add its bin directory to PATH.' >&2
    exit 1
fi

printf '%s\n' 'Building the setup tool...'
mvn -pl tool -am clean package
printf '%s\n' 'Starting MCP Remastered...'
exec java -jar tool/target/mcp-remastered-1.0.0.jar "$@"
