#!/bin/sh

daemon=true
#filebeat=filebeat.log,filebeat2.log
logfile=/var/log/logserver/all.logs
jarfile=target/logserver.jar
[ ! -e "$jarfile" ] && jarfile=logserver.jar
Survivor=1 Old=16 NewSize=$[Survivor*10] Xmx=$[NewSize+Old] #NewSize=Survivor*(1+1+8) Xmx=NewSize+Old
JVM_OPS="-Xmx${Xmx}m -Xms${Xmx}m -XX:NewSize=${NewSize}m -XX:MaxNewSize=${NewSize}m -XX:SurvivorRatio=8 -Xss228k"
#JVM_OPS="$JVM_OPS -Dredis -Dredis.host=localhost -Dredis.port=6379 -Dredis.pubsub=true -Dredis.pushpop=true -Dredis.queueSize=10240"
JVM_OPS="$JVM_OPS -Djava.compiler=none -Dlajax.token=xlongwei -DcontextName=logserver -DlogLength=2048"
ENV_OPS="$ENV_OPS accessKeyId=7sTaWT0zAVYmtxlq secret=`[ -e /etc/aliyun.secret ] &&  cat /etc/aliyun.secret`"
ENV_OPS="$ENV_OPS regionId=cn-hangzhou domainName=xlongwei.com recordId=4012091293697024"
JVM_OPS="$JVM_OPS -Dfiles=false -Dlogger=logserver@log"
#JVM_OPS="$JVM_OPS -Xdebug -Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n"
ENV_OPS="$ENV_OPS workerThreads=1 ioThreads=1 enableHttps=false"
#ENV_OPS="$ENV_OPS PATH=/usr/java/jdk1.8.0_161/bin:$PATH"

usage(){
    echo "Usage: start.sh ( commands ... )"
    echo "commands: "
    echo "  status      check the running status"
    echo "  start       start logserver"
    echo "  stop        stop logserver"
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
	    echo "logserver is not running!"
	else
		for PID in $PIDS ; do
		    echo "logserver has pid: $PID!"
		done
	fi
}

stop(){
    PIDS=`ps -ef | grep java | grep "$jarfile" |awk '{print $2}'`

	if [ -z "$PIDS" ]; then
	    echo "logserver is not running!"
	else
		echo -e "Stopping logserver ..."
		for PID in $PIDS ; do
			echo -e "kill $PID"
		    kill -9 $PID > /dev/null 2>&1
		done
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
	echo "starting logserver ..."
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
	restart) stop && start ;;
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
