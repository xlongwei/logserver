if [[ ! "$#" -eq 3 ]];then
echo "usage: $0 metricName, value, appName"
exit 1
fi
#/soft/shells/cms_post.sh 1892708693921942 $1 $2 $3
echo "appName=$3,metricName=$1,value=$2"
curl -s http://localhost:9880/log?type=metric\&metricName=$1\&value=$2\&appName=$3 2>&1 > /dev/null
