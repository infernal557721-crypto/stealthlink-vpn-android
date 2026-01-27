#!/bin/sh

#
# Gradle wrapper script for UNIX-like operating systems.
#
# Important environment variables:
#   JAVA_HOME - location of a JDK home dir
#   GRADLE_OPTS - options to build JVM
#

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
app_path=$0

# Need this for daisy-chained symlinks
while
    APP_HOME=${app_path%"${app_path##*/}"}
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in
      /*)   app_path=$link ;;
      *)    app_path=$APP_HOME$link ;;
    esac
done

APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit
APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}

# Use the maximum available, or set MAX_FD != -1 to use that value
MAX_FD=maximum

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# OS specific support
cygwin=false
darwin=false
nonstop=false
msys=false
case "$( uname )" in
  CYGWIN* )         cygwin=true  ;;
  Darwin* )         darwin=true  ;;
  MSYS* | MINGW* )  msys=true    ;;
  NONSTOP* )        nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD=java
    if ! command -v java > /dev/null 2>&1
    then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
fi

# Increase the maximum file descriptors if we can
if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    case $MAX_FD in
      max*)
        # Calculate max fd value
        MAX_FD=$( ulimit -H -n ) ||
            warn "Could not query maximum file descriptor limit"
    esac
    case $MAX_FD in
      '' | soft) :;;
      *)
        ulimit -n "$MAX_FD" ||
            warn "Could not set maximum file descriptor limit to $MAX_FD"
    esac
fi

# For Darwin, add options to specify how the application appears in the dock
if "$darwin" ; then
    GRADLE_OPTS="$GRADLE_OPTS \"-Xdock:name=$APP_NAME\" \"-Xdock:icon=$APP_HOME/media/gradle.icns\""
fi

# Collect all arguments for the java command
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Download gradle-wrapper.jar if it doesn't exist
WRAPPER_JAR=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading Gradle Wrapper..."
    mkdir -p "$APP_HOME/gradle/wrapper"
    # Try to download from Gradle services
    if command -v curl > /dev/null 2>&1; then
        curl -fsSL -o "$WRAPPER_JAR" "https://github.com/gradle/gradle/raw/v8.5.0/gradle/wrapper/gradle-wrapper.jar" || \
        curl -fsSL -o "$WRAPPER_JAR" "https://raw.githubusercontent.com/nicowilliams/gradle-wrapper/main/gradle-wrapper.jar" || \
        die "ERROR: Could not download gradle-wrapper.jar"
    elif command -v wget > /dev/null 2>&1; then
        wget -q -O "$WRAPPER_JAR" "https://github.com/gradle/gradle/raw/v8.5.0/gradle/wrapper/gradle-wrapper.jar" || \
        wget -q -O "$WRAPPER_JAR" "https://raw.githubusercontent.com/nicowilliams/gradle-wrapper/main/gradle-wrapper.jar" || \
        die "ERROR: Could not download gradle-wrapper.jar"
    else
        die "ERROR: Could not download gradle-wrapper.jar (neither curl nor wget found)"
    fi
fi

# Collect all arguments for the java command
set -- \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"

# Use "exec" to replace shell with java process
exec "$JAVACMD" "$@"
