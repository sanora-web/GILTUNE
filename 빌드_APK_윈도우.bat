@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo =======================================
echo GILTUNE Android debug APK build
 echo =======================================
call gradlew.bat :app:testDemoDebugUnitTest :app:assembleDemoDebug
if errorlevel 1 (
 echo.
 echo [ERROR] Build failed. Open the Build output in Android Studio.
 pause
 exit /b 1
)
echo.
echo [OK] debug APK generated (NOT signed release APK).
explorer "app\build\outputs\apk\demo\debug"
pause
