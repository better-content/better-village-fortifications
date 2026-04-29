#!/usr/bin/env sh

##############################################################################
##
##  Gradle start up script for Unix
##
##############################################################################

##############################################################################
# Based on the script shipped with Gradle distributions.
##############################################################################

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
APP_HOME=$(dirname "$0")

if [ -z "$JAVA_HOME" ] ; then
    JAVA_HOME="$(command -v java | sed 's:/bin/java$::')"
fi

if [ ! -x "$JAVA_HOME/bin/java" ] ; then
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found."
    exit 1
fi

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec "$JAVA_HOME/bin/java" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
