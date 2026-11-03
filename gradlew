#!/bin/sh
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
if [ -n "$JAVA_HOME" ]; then JAVA_BIN="$JAVA_HOME/bin/java"; else JAVA_BIN="java"; fi
exec "$JAVA_BIN" -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
