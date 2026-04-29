@if "%DEBUG%" == "" @echo off
@rem Gradle startup script for Windows
@setlocal

@set APP_NAME=Gradle
@set APP_BASE_NAME=%~n0
@set APP_HOME=%~dp0

if defined JAVA_HOME goto findJavaHome
for %%i in (java.exe) do %%~$PATH:i (
  set JAVA_EXE=%%i
)
if defined JAVA_EXE goto execute
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in PATH.
exit /b 1

:findJavaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo ERROR: Cannot find java.exe.
exit /b 1

:execute
@set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
@%JAVA_EXE% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
