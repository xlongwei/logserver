#!/bin/bash
if [[ ! "$#" -eq 2 ]];then
echo "usage: $0 appName pid"
exit 1
fi
appName=$1
pid=$2
gcutil=`jstat -gcutil $pid | tail -1`
old=`echo $gcutil | awk '{print $4}'`
#echo "old=$old"
/soft/shells/cms_upload.sh old $old $appName

ygc=`echo $gcutil | awk '{print $7}'`
fgc=`echo $gcutil | awk '{print $9}'`
gct=`echo $gcutil | awk '{print $11}'`
avg=$(echo "scale=0; $gct * 1000 / ( $ygc + $fgc )" | bc)
#echo "YGC=$ygc,FGC=$fgc,GCT=$gct,AVG=$avg"
/soft/shells/cms_upload.sh gct $avg $appName
