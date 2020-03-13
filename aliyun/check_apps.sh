#!/bin/bash

check_java(){
  appName="$1"
  #echo $appName
  pid=`ps -ef | grep java | grep "$appName.jar" | head -n 1 | awk '{print $2}'`
  #echo $pid
  if [[ -z $pid ]];then
    echo "$appName not running"
    return
  fi

  echo "appName=$appName,pid=$pid"
  /soft/shells/check_threads.sh $appName $pid
  /soft/shells/check_gc.sh $appName $pid
}

check_java logserver
check_java light4j

lines=`ps -ef|wc -l`
procs=$(echo "$lines - 1" | bc)
/soft/shells/cms_upload.sh procs $procs xlongwei
