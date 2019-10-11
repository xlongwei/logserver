## logserver

##### 项目简介
使用logback和light-4j构建的简单日志服务，参考项目[logbackserver](https://gitee.com/xlongwei/logbackserver)和[light4j](https://gitee.com/xlongwei/light4j)。

##### 本地测试

1. 项目构建：mvn package dependency:copy-dependencies -DoutputDirectory=target
2. 运行服务：start.bat，打开首页[index](http://localhost:9880/index.html)，点开[tail](http://localhost:9880/tail.html)跟踪日志
3. client测试：client.bat，输入测试内容，浏览器会输出最新日志

##### 线上部署

1. 项目打包：sh start.sh deploy，打包为单独的fat-jar
2. 运行服务：sh start.sh start，也可以java -jar target/logserver.jar
3. 其他项目的日志配置参考client.xml，或者参考[light4j](https://gitee.com/xlongwei/light4j/blob/master/src/main/resources/logback.xml)
4. -Dlogfile=logs/all.logs 日志路径，logserver自身日志输出到Console，其他client应用日志输出到logfile

##### 演示图

演示地址：[https://log.xlongwei.com/](https://log.xlongwei.com/)

![index](http://t.xlongwei.com/images/logserver/index.png)

![tail](http://t.xlongwei.com/images/logserver/tail.png)

![page](http://t.xlongwei.com/images/logserver/page.png)

##### Nginx配置

登录认证见[pass.db](http://api.xlongwei.com/doku.php?id=tools:logstation)生成命令。

	server {
	        server_name log.xlongwei.com;
	        #auth_basic "User Authentication";
	        #auth_basic_user_file /usr/local/nginx/conf/pass.db;
	        location / {
	                proxy_pass http://your_ip:9880;
	                proxy_http_version 1.1;
	                proxy_set_header Upgrade $http_upgrade;
	                proxy_set_header Connection "upgrade";
	        }
	        location /files {
	                alias /soft/shares/logs;
	                autoindex on;
	                add_header Content-Type: "text/plain;charset=UTF-8";
	        }
	}

