@ECHO OFF
SETLOCAL
SET DIRNAME=%~dp0
IF "%DIRNAME%"=="" SET DIRNAME=.
SET APP_BASE_NAME=%~n0
SET APP_HOME=%DIRNAME%

IF DEFINED JAVA_HOME GOTO findJavaFromJavaHome
SET JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
IF %ERRORLEVEL% EQU 0 GOTO execute
ECHO ERROR: JAVA_HOME is not set and no 'java' command could be found. 1>&2
EXIT /B 1

:findJavaFromJavaHome
SET JAVA_HOME=%JAVA_HOME:"=%
SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
IF EXIST "%JAVA_EXE%" GOTO execute
ECHO ERROR: JAVA_HOME points to an invalid directory: %JAVA_HOME% 1>&2
EXIT /B 1

:execute
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
ENDLOCAL
