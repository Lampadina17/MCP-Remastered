@echo off
setlocal EnableExtensions DisableDelayedExpansion
pushd "%~dp0" || exit /b 1
set "JAVA=java"
if not defined JAVA_HOME goto java_path
set "JAVA=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA%" (
    echo ERROR: JAVA_HOME must point to a JDK 25 or newer. 1>&2
    goto fail
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

:java_path
where javac >nul 2>nul
if errorlevel 1 (
    echo ERROR: Install JDK 25+ and add its bin directory to PATH. 1>&2
    goto fail
)
set "VERSION_FILE=%TEMP%\mcp-java-%RANDOM%-%RANDOM%.txt"
"%JAVA%" --version >"%VERSION_FILE%" 2>nul
if errorlevel 1 goto bad_java
set "JAVA_VERSION="
for /f "usebackq tokens=2" %%V in ("%VERSION_FILE%") do if not defined JAVA_VERSION set "JAVA_VERSION=%%V"
del "%VERSION_FILE%" >nul 2>nul
set "JAVA_MAJOR="
for /f "tokens=1 delims=.-" %%M in ("%JAVA_VERSION%") do set "JAVA_MAJOR=%%M"
if not defined JAVA_MAJOR goto bad_java
echo(%JAVA_MAJOR%| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 goto bad_java
if %JAVA_MAJOR% LSS 25 goto bad_java
where mvn >nul 2>nul
if errorlevel 1 (
    echo ERROR: Install Maven and add its bin directory to PATH. 1>&2
    goto fail
)

echo Building the setup tool...
call mvn -pl tool -am clean package
if errorlevel 1 goto fail
echo Starting MCP Remastered...
"%JAVA%" -jar "tool\target\mcp-remastered-1.0.0.jar" %*
if errorlevel 1 goto fail
popd
exit /b 0

:bad_java
if defined VERSION_FILE if exist "%VERSION_FILE%" del "%VERSION_FILE%" >nul 2>nul
echo ERROR: Setup requires Java 25+. Set JAVA_HOME and PATH to a JDK 25 or newer. 1>&2
:fail
echo Setup failed. See the error above; the next stage was not started. 1>&2
popd
exit /b 1
