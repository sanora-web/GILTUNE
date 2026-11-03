@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
echo ======================================
echo GILTUNE Pleos SDK - Debug APK build
echo ======================================
echo Uses official SDK dependencies; requires Gradle Maven connectivity.
echo.
call gradlew.bat --no-daemon :app:testPleosDebugUnitTest :app:assemblePleosDebug > pleos-build-log.txt 2>&1
if errorlevel 1 (
  echo [FAILED] SDK download or compile failed.
  echo See pleos-build-log.txt. Send this log for diagnosis.
  type pleos-build-log.txt | findstr /C:"FAILURE:" /C:"What went wrong" /C:"Could not" /C:"error:"
  pause
  exit /b 1
)
if not exist "app\build\outputs\apk\pleos\debug\app-pleos-debug.apk" (
  echo [FAILED] APK not found despite a successful task. Check pleos-build-log.txt.
  pause
  exit /b 2
)
if not exist apk mkdir apk
copy /y "app\build\outputs\apk\pleos\debug\app-pleos-debug.apk" "apk\GILTUNE_PLEOS_SDK_TEST.apk" >nul
echo.
echo [SUCCESS] apk\GILTUNE_PLEOS_SDK_TEST.apk
pause
