@echo off
chcp 65001 >nul 2>&1
REM JPostman (Swing) launcher
REM Requires JDK 8+

set "JAR_DIR=%~dp0"
set "JAR_FILE=%JAR_DIR%jpostman-swing\target\jpostman-swing-0.1.0-SNAPSHOT.jar"

if not exist "%JAR_FILE%" (
    echo [ERROR] JAR not found: %JAR_FILE%
    echo Please run: mvn clean package -DskipTests
    pause
    exit /b 1
)

echo Starting JPostman (Swing)...
java -jar "%JAR_FILE%"
