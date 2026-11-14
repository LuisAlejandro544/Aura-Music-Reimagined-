@rem Script de Gradle Wrapper (8.14.7) para Windows (Aura Music).
@if "%DEBUG%"=="" @echo off
setlocal
set GRADLE_VERSION=8.14.7
set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" (
  echo Generando gradle-wrapper.jar oficial para Gradle %GRADLE_VERSION%...
  where gradle >nul 2>nul
  if %ERRORLEVEL%==0 (
    pushd "%APP_HOME%"
    gradle wrapper --gradle-version %GRADLE_VERSION% --no-daemon
    popd
  ) else (
    powershell -Command "$tmp = Join-Path $env:TEMP ('gradle-' + [guid]::NewGuid()); New-Item -ItemType Directory -Path $tmp | Out-Null; $zip = Join-Path $tmp 'gradle.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; Expand-Archive -Path $zip -DestinationPath $tmp; Push-Location '%APP_HOME%'; & (Join-Path $tmp 'gradle-%GRADLE_VERSION%\bin\gradle.bat') wrapper --gradle-version %GRADLE_VERSION% --no-daemon; Pop-Location; Remove-Item -Recurse -Force $tmp"
  )
)

set JAVA_EXE=java.exe
if defined JAVA_HOME set JAVA_EXE=%JAVA_HOME%\bin\java.exe
"%JAVA_EXE%" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
