usage(){
    echo "Usage: start.sh ( commands ... )"
    echo "commands: "
    echo "  status		check the running status"
    echo "  start		start logserver"
    echo "  stop		stop logserver"
    echo "  restart		stop && start"
    echo "  clean		clean target"
    echo "  build		build logserver.jar"
    echo "  jars		copy dependencies to target"
    echo "  package		build logser.jar and copy dependencies to target"
    echo "  rebuild		stop && build && start"
    echo "  refresh		stop && clean && build && jars && start"
    echo "  deploy		package all to one-fat logserver.jar"
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
	JVM_OPS="-server -Xmx256M"
	env enableHttps=false setsid java $JVM_OPS -Dlogfile=/usr/nfs/logs/all.logs -jar target/logserver.jar >> /dev/null 2>&1 &
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
	build) jar ;;
	jars) dependency ;;
	package) jar && dependency ;;
	rebuild) stop && jar && start ;;
	refresh) stop && clean && jar && dependency && start ;;
	deploy) deploy ;;
	*) usage ;;
	esac
fi
