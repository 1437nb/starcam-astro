@echo off
rem Local source build; no cleanup or external prebuilt static libraries.
if "%~1"=="" (
  python "%~dp0build_native.py" --fetch --output "%~dp0..\code\app\src\main\jniLibs\arm64-v8a\libstellar_solver.so"
) else (
  python "%~dp0build_native.py" --fetch --output "%~1"
)
exit /b %errorlevel%
