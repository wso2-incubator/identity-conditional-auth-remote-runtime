# WSO2 Identity GraalJS Runtime

This document explains how to start and run the WSO2 Identity GraalJS Runtime without using the previous `runtime.sh` and `runtime.bat` scripts.

---

# Prerequisites

## Java

The runtime supports JDK versions between 11 and 21.

Set the `JAVA_HOME` environment variable before running the runtime.

### Linux / macOS

```bash
export JAVA_HOME=/path/to/jdk
```

### Windows

```bat
set JAVA_HOME=C:\path\to\jdk
```

---

# Runtime Home

Set the runtime home directory.

### Linux / macOS

```bash
export GRAALJS_RUNTIME_HOME=/path/to/org.wso2.identity.graaljs.external.runtime
```

### Windows

```bat
set GRAALJS_RUNTIME_HOME=C:\path\to\org.wso2.identity.graaljs.external.runtime
```

---

# Optional JVM Configuration

## Memory Configuration

### Linux / macOS

```bash
export JVM_MEM_OPTS="-Xms256m -Xmx512m"
```

### Windows

```bat
set JVM_MEM_OPTS=-Xms256m -Xmx512m
```

---

## Additional Java Options

### Linux / macOS

```bash
export JAVA_OPTS="<additional-java-options>"
```

### Windows

```bat
set JAVA_OPTS=<additional-java-options>
```

---

# Running the Runtime

# Linux / macOS

## Build Runtime Classpath

### Extracted Distribution

```bash
RUNTIME_CLASSPATH="$GRAALJS_RUNTIME_HOME/conf"

for f in "$GRAALJS_RUNTIME_HOME"/lib/*.jar; do
  [ -f "$f" ] && RUNTIME_CLASSPATH="$RUNTIME_CLASSPATH":$f
done
```

### Source Tree After Maven Build

```bash
RUNTIME_CLASSPATH="$GRAALJS_RUNTIME_HOME/conf"

for f in "$GRAALJS_RUNTIME_HOME"/target/wso2is-graaljs-runtime-*.jar; do
  case "$f" in
    *original-*) continue ;;
  esac
  [ -f "$f" ] && RUNTIME_CLASSPATH="$RUNTIME_CLASSPATH":$f
done

for f in "$GRAALJS_RUNTIME_HOME"/target/lib/*.jar; do
  [ -f "$f" ] && RUNTIME_CLASSPATH="$RUNTIME_CLASSPATH":$f
done
```

---

## Java Version Based Options

```bash
JAVA_VER_BASED_OPTS="--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.io=ALL-UNNAMED \
--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.security=ALL-UNNAMED \
--add-opens=java.base/sun.security.util=ALL-UNNAMED"
```

---

## Run in Foreground

```bash
cd "$GRAALJS_RUNTIME_HOME"

if [ -z "$JVM_MEM_OPTS" ]; then
  JVM_MEM_OPTS="-Xms256m -Xmx512m"
fi

java \
$JVM_MEM_OPTS \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath="$GRAALJS_RUNTIME_HOME/repository/logs/heap-dump.hprof" \
$JAVA_OPTS \
-classpath "$RUNTIME_CLASSPATH" \
$JAVA_VER_BASED_OPTS \
-Dgraaljs.runtime.home="$GRAALJS_RUNTIME_HOME" \
-Dconf.location="$GRAALJS_RUNTIME_HOME/conf" \
-Djava.io.tmpdir="$GRAALJS_RUNTIME_HOME/tmp" \
-Dfile.encoding=UTF8 \
-Djava.net.preferIPv4Stack=true \
-Djava.security.egd=file:/dev/./urandom \
org.wso2.carbon.identity.graaljs.External.Main
```

---

## Run in Debug Mode

```bash
export JAVA_OPTS="-Xdebug -Xnoagent -Djava.compiler=NONE \
-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
```

Then execute the foreground run command.

---

## Run in Background

```bash
nohup java \
$JVM_MEM_OPTS \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath="$GRAALJS_RUNTIME_HOME/repository/logs/heap-dump.hprof" \
$JAVA_OPTS \
-classpath "$RUNTIME_CLASSPATH" \
$JAVA_VER_BASED_OPTS \
-Dgraaljs.runtime.home="$GRAALJS_RUNTIME_HOME" \
-Dconf.location="$GRAALJS_RUNTIME_HOME/conf" \
-Djava.io.tmpdir="$GRAALJS_RUNTIME_HOME/tmp" \
-Dfile.encoding=UTF8 \
-Djava.net.preferIPv4Stack=true \
-Djava.security.egd=file:/dev/./urandom \
org.wso2.carbon.identity.graaljs.External.Main \
> runtime.log 2>&1 &
```

---

# Windows

## Build Runtime Classpath

### Extracted Distribution

```bat
set RUNTIME_CLASSPATH=.\conf

FOR %%C in ("%GRAALJS_RUNTIME_HOME%\lib\*.jar") DO (
  set RUNTIME_CLASSPATH=!RUNTIME_CLASSPATH!;"%%C"
)
```

### Source Tree After Maven Build

```bat
set RUNTIME_CLASSPATH=.\conf

FOR %%R in ("%GRAALJS_RUNTIME_HOME%\target\wso2is-graaljs-runtime-*.jar") DO (
  echo %%~nR | findstr /B "original-" >nul || set RUNTIME_CLASSPATH=!RUNTIME_CLASSPATH!;"%%R"
)

FOR %%C in ("%GRAALJS_RUNTIME_HOME%\target\lib\*.jar") DO (
  set RUNTIME_CLASSPATH=!RUNTIME_CLASSPATH!;"%%C"
)
```

---

## Java Version Based Options

```bat
set JAVA_VER_BASED_OPTS=--add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.security=ALL-UNNAMED --add-opens=java.base/sun.security.util=ALL-UNNAMED
```

---

## Run in Foreground

```bat
cd %GRAALJS_RUNTIME_HOME%

if "%JVM_MEM_OPTS%"=="" set JVM_MEM_OPTS=-Xms256m -Xmx512m

"%JAVA_HOME%\bin\java" ^
%JVM_MEM_OPTS% ^
-XX:+HeapDumpOnOutOfMemoryError ^
-XX:HeapDumpPath="%GRAALJS_RUNTIME_HOME%\repository\logs\heap-dump.hprof" ^
%JAVA_OPTS% ^
-classpath %RUNTIME_CLASSPATH% ^
%JAVA_VER_BASED_OPTS% ^
-Dgraaljs.runtime.home="%GRAALJS_RUNTIME_HOME%" ^
-Dconf.location="%GRAALJS_RUNTIME_HOME%\conf" ^
-Djava.io.tmpdir="%GRAALJS_RUNTIME_HOME%\tmp" ^
-Dfile.encoding=UTF8 ^
-Djava.net.preferIPv4Stack=true ^
org.wso2.carbon.identity.graaljs.External.Main
```

---

## Run in Debug Mode

```bat
set JAVA_OPTS=-Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005
```

Then execute the foreground run command.

---

# Version Information

## Linux / macOS

```bash
cat "$GRAALJS_RUNTIME_HOME/bin/version.txt"
```

## Windows

```bat
type "%GRAALJS_RUNTIME_HOME%\bin\version.txt"
```

---

# Heap Dump Location

```text
repository/logs/heap-dump.hprof
```

---

# Temporary Directory

```text
tmp/
```

---

# Main Runtime Class

```text
org.wso2.carbon.identity.graaljs.External.Main
```