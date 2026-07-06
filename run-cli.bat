@echo off
chcp 65001 >nul 2>&1
REM JPostman CLI (curl-compatible) launcher
REM Requires JDK 17+, JAVA_HOME must point to JDK directory

if "%JAVA_HOME%"=="" (
    echo [ERROR] JAVA_HOME is not set.
    echo Please set JAVA_HOME to your JDK installation directory.
    exit /b 1
)

set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
set "JAR_DIR=%~dp0"
set "JAR_FILE=%JAR_DIR%jpostman-core\target\jpostman-core-0.1.0-SNAPSHOT-exec.jar"

if not exist "%JAR_FILE%" (
    echo [ERROR] JAR not found: %JAR_FILE%
    echo Please run: mvn clean package -DskipTests
    exit /b 1
)

"%JAVA_EXE%" --add-modules jdk.compiler -jar "%JAR_FILE%" %*
