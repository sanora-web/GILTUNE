@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
echo GILTUNE Android demo - offline MP3 (no Pleos SDK included)
call gradlew.bat --no-daemon :app:testDemoDebugUnitTest :app:assembleDemoDebug > demo-build-log.txt 2>&1
if errorlevel 1 (
  echo [FAILED] See demo-build-log.txt
  pause
  exit /b 1
)
if not exist apk mkdir apk
copy /y "app\build\outputs\apk\demo\debug\app-demo-debug.apk" "apk\GILTUNE_ANDROID_DEMO.apk" >nul
echo [SUCCESS] apk\GILTUNE_ANDROID_DEMO.apk
pause
