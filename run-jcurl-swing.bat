@echo off
chcp 65001 >nul 2>&1
REM Jcurl (Swing) launcher
REM Requires JDK 8+ (JDK required for .java plugin compilation)

set "JAR_DIR=%~dp0"
set "JAR_FILE=%JAR_DIR%jcurl-swing\target\jcurl-swing-0.1.0-SNAPSHOT.jar"

if not exist "%JAR_FILE%" (
    echo [ERROR] JAR not found: %JAR_FILE%
    echo Please run: mvn clean package -DskipTests
    pause
    exit /b 1
)

REM Determine Java executable: prefer JAVA_HOME (usually JDK), fallback to PATH
set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

REM For Java 9+ JDK, add --add-modules jdk.compiler so ToolProvider can find the compiler.
REM Java 8 JDK: ToolProvider works via tools.jar (no --add-modules needed).
REM JRE (any version): jdk.compiler module absent, --add-modules would fail, so skip it.
set "JAVA_OPTS="
"%JAVA_EXE%" --list-modules 2>nul | findstr "jdk.compiler" >nul && set "JAVA_OPTS=--add-modules jdk.compiler"

echo Starting Jcurl (Swing)...
"%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%"
