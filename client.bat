@echo off
REM This script will add logback jars to your classpath.

set LB_HOME=target
REM echo %LB_HOME%

set LB_VERSION=1.2.3
set API_VERSION=1.7.25

set CLASSPATH=%CLASSPATH%;%LB_HOME%/logback-classic-%LB_VERSION%.jar
set CLASSPATH=%CLASSPATH%;%LB_HOME%/logback-core-%LB_VERSION%.jar
REM set CLASSPATH=%CLASSPATH%;%LB_HOME%/logback-examples-%LB_VERSION%.jar
set CLASSPATH=%CLASSPATH%;target/classes
del /f target\classes\logback.xml
set CLASSPATH=%CLASSPATH%;%LB_HOME%/slf4j-api-%API_VERSION%.jar

echo %CLASSPATH%

REM log event to server:6000
REM java chapters.appenders.socket.SocketClient1 localhost 6000

REM SOCKET: log event to server:6000, SERVERSOCKET: log listen on 4560
REM java chapters.appenders.socket.SocketClient2 client.xml
java com.xlongwei.logserver.SocketClient2 client.xml