@echo off
rem ============================================================================
rem 本机构建 libstellar_solver.so（arm64-v8a）—— Windows 原生，无需 Linux/服务器
rem ============================================================================
rem
rem 依赖（一次性准备，已就位）：
rem   C:\dev\astrometry-local\
rem     ├── src\include\astrometry\    astrometry 0.97 头文件
rem     ├── lib\*.a                    astrometry 静态库（arm64，Linux ELF 可直链）
rem     └── cross\{lib,include}\       cfitsio 3.47 交叉产物
rem   C:\dev\android-ndk-r26d\         NDK r26d（Windows 原生 clang）
rem
rem 关键实测（2026-09-17）：Windows 版 NDK clang 能直接链接 Linux ELF 格式的
rem .a 静态库 —— 不需要 WSL/虚拟机。这是把构建搬回本机的前提。
rem
rem 用法:  build_so.bat [输出路径]
rem   默认输出到 apk 归档旁的 jniLibs 目录
rem ============================================================================
setlocal

set "NDK=C:\dev\android-ndk-r26d\toolchains\llvm\prebuilt\windows-x86_64\bin"
set "CC=%NDK%\aarch64-linux-android21-clang.cmd"
set "NM=%NDK%\llvm-nm.exe"

set "AN=C:\dev\astrometry-local"
set "CROSS=%AN%\cross"
set "SRC=C:\starword\code\app\src\main\cpp\astro_bridge.c"

if "%~1"=="" (
  set "OUT=C:\starword\code\app\src\main\jniLibs\arm64-v8a\libstellar_solver.so"
) else (
  set "OUT=%~1"
)

if not exist "%CC%" ( echo [错误] 找不到 NDK clang: %CC% & exit /b 1 )
if not exist "%SRC%" ( echo [错误] 找不到桥接源: %SRC% & exit /b 1 )
if not exist "%AN%\lib\solver\libastrometry.a" ( echo [错误] 静态库缺失 & exit /b 1 )
if not exist "%CROSS%\lib\libcfitsio.a" ( echo [错误] cfitsio 缺失 & exit /b 1 )

set "WORK=%TEMP%\sobuild_%RANDOM%"
mkdir "%WORK%" 2>nul

echo [1/2] 编译 astro_bridge.c (arm64-v8a, API 21)...
call "%CC%" -O2 -fPIC -DANDROID -pthread ^
  -I"%AN%\src\include" -I"%CROSS%\include" ^
  -c "%SRC%" -o "%WORK%\astro_bridge.o"
if errorlevel 1 ( echo [错误] 编译失败 & rmdir /s /q "%WORK%" & exit /b 1 )

echo [2/2] 链接 libstellar_solver.so...
call "%CC%" -shared -o "%OUT%" "%WORK%\astro_bridge.o" ^
  -Wl,--allow-multiple-definition ^
  "%AN%\lib\solver\libastrometry.a" ^
  "%AN%\lib\util\libanutils.a" ^
  "%AN%\lib\util\libanfiles.a" ^
  "%AN%\lib\util\libanbase.a" ^
  "%AN%\lib\catalogs\libcatalogs.a" ^
  "%AN%\lib\libkd\libkd.a" ^
  "%AN%\lib\qfits-an\libqfits.a" ^
  "%AN%\lib\gsl-an\libgsl-an.a" ^
  "%CROSS%\lib\libcfitsio.a" ^
  -lm -ldl -lz
if errorlevel 1 ( echo [错误] 链接失败 & rmdir /s /q "%WORK%" & exit /b 1 )

rmdir /s /q "%WORK%" 2>nul
echo.
echo 产物: %OUT%
for %%F in ("%OUT%") do echo 大小: %%~zF 字节
echo.
echo 导出符号（应含 extractStars/solve/solvePriors/releaseIndexes/indexCacheStats）:
"%NM%" -D --defined-only "%OUT%" | findstr "Java_com_starcam"
endlocal
