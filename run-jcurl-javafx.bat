@echo off
chcp 65001 >nul 2>&1
REM Jcurl (JavaFX) launcher
REM Requires JDK 17+, JAVA_HOME must point to JDK directory

if "%JAVA_HOME%"=="" (
    echo [ERROR] JAVA_HOME is not set.
    echo Please set JAVA_HOME to your JDK installation directory.
    pause
    exit /b 1
)

set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" (
    echo [ERROR] java.exe not found at: %JAVA_EXE%
    pause
    exit /b 1
)

set "JAR_DIR=%~dp0"
set "JAR_FILE=%JAR_DIR%jcurl-javafx\target\jcurl-javafx-0.1.0-SNAPSHOT.jar"

if not exist "%JAR_FILE%" (
    echo [ERROR] JAR not found: %JAR_FILE%
    echo Please run: mvn clean package -DskipTests
    pause
    exit /b 1
)

echo Starting Jcurl (JavaFX)...
"%JAVA_EXE%" --add-modules jdk.compiler -jar "%JAR_FILE%"
