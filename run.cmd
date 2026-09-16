@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul
set "JAVA_HOME=C:\Program Files (x86)\Android\openjdk\jdk-17.0.14"
set "PATH=%JAVA_HOME%\bin;%PATH%"
java -version
if errorlevel 1 (
  echo 未找到 Java，请先安装 JDK 17 并配置 JAVA_HOME 和 Path。
  pause
  exit /b 1
)
set "APP_JAR=app\metrics-agent.jar"
if exist "target\metrics-agent.jar" set "APP_JAR=target\metrics-agent.jar"
if not exist "%APP_JAR%" (
  echo 找不到运行包，请执行 mvnw.cmd clean package。
  pause
  exit /b 1
)
echo 启动后打开 http://127.0.0.1:8082 ，请勿关闭此窗口。
java -Dfile.encoding=UTF-8 -jar "%APP_JAR%"
pause
