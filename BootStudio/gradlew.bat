@echo off
REM Gradle startup script for Windows

setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.

set GRADLE_HOME=%DIRNAME%gradle
set PATH=%GRADLE_HOME%\bin;%PATH%

if exist "%DIRNAME%gradle\wrapper\gradle-wrapper.properties" (
    call "%DIRNAME%gradle\wrapper\gradle-wrapper.bat" %*
) else (
    echo "Gradle wrapper not found."
    exit /b 1
)

endlocal
exit /b 0