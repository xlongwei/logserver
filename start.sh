#!/bin/sh

daemon=true
appname=logserver
#filebeat=filebeat.log,filebeat2.log
logfile=/var/log/logserver/all.logs
jarfile=target/$appname.jar
pwdfile=./my.pwd
[ ! -e "$jarfile" ] && jarfile=$appname.jar
[ -e $pwdfile ] && source $pwdfile
Survivor=1 Old=16 NewSize=$[Survivor*10] Xmx=$[NewSize+Old] #NewSize=Survivor*(1+1+8) Xmx=NewSize+Old
JVM_OPS="-Xmx${Xmx}m -Xms${Xmx}m -XX:NewSize=${NewSize}m -XX:MaxNewSize=${NewSize}m -XX:SurvivorRatio=8 -Xss228k"
#JVM_OPS="$JVM_OPS -Dredis -Dredis.host=localhost -Dredis.port=6379 -Dredis.pubsub=true -Dredis.pushpop=true -Dredis.queueSize=10240"
JVM_OPS="$JVM_OPS -Djava.compiler=none -Dlajax.token=${token:-xlongwei} -DcontextName=$appname -DlogLength=2048 -Dlogback.configurationFile=classpath:logback.xml"
ENV_OPS="$ENV_OPS accessKeyId=${accessKeyId:-} secret=${secret:-}"
ENV_OPS="$ENV_OPS regionId=cn-hangzhou domainName=xlongwei.com recordId=4012091293697024"
JVM_OPS="$JVM_OPS -Dfiles=false -Dlogger=logserver@log -Dmask=passw(3,15);token(3,15)"
#JVM_OPS="$JVM_OPS -Dfiles=true -Dlogger=logserver@log,apidoc@https://api.xlongwei.com/apidoc/demo/log.htm,bpmdemo@https://bpm.xlongwei.com/demo/demo/log,cms@https://cms.xlongwei.com/demo/log.json,light4j@https://api.xlongwei.com/demo/log,search@https://log.xlongwei.com/service/logserver/log"
#JVM_OPS="$JVM_OPS -Dlogger=logserver@log,apidoc@http://localhost:8081/apidoc/demo/log.htm,bpmdemo@http://localhost:8080/demo/demo/log,cms@http://localhost:8081/demo/log.json,light4j@http://localhost:8080/demo/log,search@http://localhost:9200/service/logserver/log"
#JVM_OPS="$JVM_OPS -Dlight-search=http://localhost:9200 -DuseIndexer=true -DuseSearch=true"
#JVM_OPS="$JVM_OPS -Xdebug -Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n"
ENV_OPS="$ENV_OPS workerThreads=1 ioThreads=1 enableHttps=false"
#ENV_OPS="$ENV_OPS PATH=/usr/java/jdk1.8.0_161/bin:$PATH"

usage(){
    echo "Usage: start.sh ( commands ... )"
    echo "commands: "
    echo "  status      check the running status"
    echo "  start       start $appname"
    echo "  stop        stop $appname"
    echo "  restart     stop && start"
    echo "  clean       clean target"
    echo "  jar         build $jarfile"
    echo "  jars        copy dependencies to target"
    echo "  package     build logser.jar and copy dependencies to target"
    echo "  rebuild     stop && build && start"
    echo "  refresh     stop && clean && build && jars && start"
    echo "  deploy      package all to one-fat $jarfile"
    echo "  redeploy    package all to one-fat $jarfile and restart"
}

status(){
    PIDS=`ps -ef | grep java | grep "$jarfile" |awk '{print $2}'`

	if [ -z "$PIDS" ]; then
	    echo "$appname is not running!"
	else
		for PID in $PIDS ; do
		    echo "$appname has pid: $PID!"
		done
	fi
}

stop(){
    PIDS=`ps -ef | grep java | grep "$jarfile" |awk '{print $2}'`

	if [ -z "$PIDS" ]; then
	    echo "$appname is not running!"
	else
		echo -e "Stopping $appname ..."
		for PID in $PIDS ; do
			echo -e "kill $PID"
		    kill $PID > /dev/null 2>&1
		done
	fi
}

wait(){
	PIDS=`ps -ef | grep java | grep "$jarfile" |awk '{print $2}'`

	if [ ! -z "$PIDS" ]; then
		COUNT=0 WAIT=9
		while [ $COUNT -lt $WAIT ]; do
			echo -e ".\c"
			sleep 1
			PIDS=`ps -ef | grep java | grep "$jarfile" |awk '{print $2}'`
			if [ -z "$PIDS" ]; then
				break
			fi
			let COUNT=COUNT+1
		done
		PIDS=`ps -ef | grep java | grep "$jarfile" |awk '{print $2}'`
		if [ ! -z "$PIDS" ]; then
			for PID in $PIDS ; do
				echo -e "kill -9 $PID"
				kill -9 $PID > /dev/null 2>&1
			done
		fi
	fi
}

clean(){
	mvn clean
}

jar(){
	mvn compile jar:jar
}

dependency(){
	mvn dependency:copy-dependencies -DoutputDirectory=target
}

deploy(){
	mvn package -Prelease -Dmaven.test.skip=true -Dmaven.javadoc.skip=true
}

start(){
	echo "starting $appname ..."
	JVM_OPS="-server -Djava.awt.headless=true $JVM_OPS"
	if [ "$daemon" = "true" ]; then
		if [ -z "$filebeat" ]; then
			env $ENV_OPS setsid java $JVM_OPS -Dlogfile=$logfile -jar $jarfile >> /dev/null 2>&1 &
		else
			env $ENV_OPS setsid java $JVM_OPS -Dlogfile=$logfile -Dfilebeat=$filebeat -cp $jarfile com.xlongwei.logserver.FileBeat >> /dev/null 2>&1 &
		fi
	else
		if [ -z "$filebeat" ]; then
			env $ENV_OPS java $JVM_OPS -jar $jarfile 2>&1
		else
			env $ENV_OPS java $JVM_OPS -Dfilebeat=$filebeat -cp $jarfile com.xlongwei.logserver.FileBeat 2>&1
		fi
	fi
}

if [ $# -eq 0 ]; then 
    usage
else
	case $1 in
	status) status ;;
	start) start ;;
	stop) stop ;;
	restart) stop && wait && start ;;
	clean) clean ;;
	jar) jar ;;
	jars) dependency ;;
	package) jar && dependency ;;
	rebuild) stop && jar && start ;;
	refresh) stop && clean && jar && dependency && start ;;
	deploy) deploy ;;
	redeploy) stop && deploy && start ;;
	*) usage ;;
	esac
fi
