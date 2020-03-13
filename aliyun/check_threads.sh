#!/bin/bash
if [[ ! "$#" -eq 2 ]];then
echo "usage: $0 appName pid"
exit 1
fi
appName=$1
pid=$2
lines=`ps Hp $pid | wc -l`
threads=$(echo "$lines - 1" | bc)
#echo "threads=$threads"
/soft/shells/cms_upload.sh threads $threads $appName
