#!/bin/sh

daemon=true
logfile=/var/log/logserver/all.logs
jarfile=target/logserver.jar
[ ! -e "$jarfile" ] && jarfile=logserver.jar
JVM_OPS="-Xmx48m -Xms48m -XX:NewSize=24m -XX:MaxNewSize=24m -Xss228k"
JVM_OPS="$JVM_OPS -DcontextName=logserver"
JVM_OPS="$JVM_OPS -DlogLength=2048"
ENV_OPS="accessKeyId=7sTaWT0zAVYmtxlq secret="
#ENV_OPS="$ENV_OPS PATH=/usr/java/jdk1.8.0_161/bin:$PATH"

usage(){
    echo "Usage: start.sh ( commands ... )"
    echo "commands: "
    echo "  status      check the running status"
    echo "  start       start logserver"
    echo "  stop        stop logserver"
    echo "  restart     stop && start"
    echo "  clean       clean target"
    echo "  jar       build $jarfile"
    echo "  jars        copy dependencies to target"
    echo "  package     build logser.jar and copy dependencies to target"
    echo "  rebuild     stop && build && start"
    echo "  refresh     stop && clean && build && jars && start"
    echo "  deploy      package all to one-fat logserver.jar"
}

status(){
    PIDS=`ps -ef | grep java | grep "logserver.jar" |awk '{print $2}'`

	if [ -z "$PIDS" ]; then
	    echo "logserver is not running!"
	else
		for PID in $PIDS ; do
		    echo "logserver has pid: $PID!"
		done
	fi
}

stop(){
    PIDS=`ps -ef | grep java | grep "logserver.jar" |awk '{print $2}'`

	if [ -z "$PIDS" ]; then
	    echo "logserver is not running!"
	else
		echo -e "Stopping logserver ..."
		for PID in $PIDS ; do
			echo -e "kill $PID"
		    kill $PID > /dev/null 2>&1
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
	mvn package -Prelease
}

start(){
	echo "starting logserver ..."
	JVM_OPS="-server -Djava.awt.headless=true $JVM_OPS"
	ENV_OPS="$ENV_OPS enableHttps=false"
	if [ "$daemon" = "true" ]; then
		env $ENV_OPS setsid java $JVM_OPS -Dlogfile=$logfile -jar $jarfile >> /dev/null 2>&1 &
	else
		env $ENV_OPS java $JVM_OPS -jar $jarfile 2>&1
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
	*) usage ;;
	esac
fi
