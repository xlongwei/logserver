## logserver

##### 项目简介
使用logback和light-4j构建的简单日志服务，参考项目[logbackserver](https://gitee.com/xlongwei/logbackserver)和[light4j](https://gitee.com/xlongwei/light4j)。logserver的设计初衷是聚合日志，解决登录多个linux主机查找日志的痛点。日志量大时使用grep方式有性能瓶颈，因此可选使用[light-search](https://gitee.com/lightgrp/light-search)创建索引和搜索。

##### 本地测试

1. 项目构建：mvn package dependency:copy-dependencies -DoutputDirectory=target
2. 运行服务：start.bat，打开首页[index](http://localhost:9880/index.html)，点开[tail](http://localhost:9880/tail.html)跟踪日志
3. client测试：client.bat，输入测试内容，浏览器会输出最新日志
4. [light-search](https://gitee.com/lightgrp/light-search)索引服务：-Dlight-search=http://localhost:9200 -DuseIndexer=true -DuseSearch=true
5. logger页签：可以动态变更应用日志级别，-Dlogger=name@url静态配置其他应用，[logserver-spring-boot-starter](https://gitee.com/xlongwei/logserver-spring-boot-starter)支持自动注册，变更时需要密码
6. [trace](https://gitee.com/xlongwei/logserver/wikis/trace)跟踪日志：提供请求头[X-Traceability-Id](http://t.xlongwei.com/images/logserver/search.png)即可，后端通过[MyCorrelationHandler](https://gitee.com/xlongwei/light4j/blob/master/src/main/resources/config/handler.yml)写入MDC，并会在跨应用请求时传递此请求头
7. sift分开存储：-Dsift，根据contextName分开存储日志，不参与搜索和索引，因此-Dlogfile依然需要，如果在root去掉ASYNC_FILE，则需要修改sift内的notify=true，以便向浏览器输出日志。
8. my.pwd保存私密信息，-Dlogback.configurationFile=/home/logback.xml可以指定外部日志配置，编辑此文件即可变更日志级别。

##### 线上部署

1. 项目打包：sh start.sh deploy，打包为单独的fat-jar
2. 运行服务：sh start.sh start，也可以java -jar target/logserver.jar
3. 其他项目的日志配置参考client.xml，或者参考[light4j](https://gitee.com/xlongwei/light4j/blob/master/src/main/resources/logback.xml)，通过/etc/hosts指定logserver
4. -Dlogfile=logs/all.logs 日志路径，logserver自身日志输出到Console，其他client应用日志输出到logfile
5. -Djava.compiler=none，禁用JIT可节约内存，默认启用JIT可提高性能
6. 定时压缩日志（logserver不再搜索），56 23 * * * sh /soft/shells/[tgz_logs.sh](https://gitee.com/xlongwei/logserver/blob/master/aliyun/tgz_logs.sh) >> /var/log/mycron_clears.log，
7. filebeat模式：vi start.sh，打开filebeat注释，用于跟踪多个日志文件，并发送日志内容到logserver，也支持redis媒介
8. 可选redis媒介：打开-Dredis注释，支持pubsub发布订阅和pushpop消息队列，日志发送方参考[logback.xml](https://gitee.com/xlongwei/light4j/blob/master/src/main/resources/logback.xml)，并且需要复制[RedisAppender](https://gitee.com/xlongwei/light4j/blob/master/src/main/java/ch/qos/logback/classic/redis/RedisAppender.java)类到正确的包下面
9. -Dfiles=false关闭files页签，-Dlogger=logserver@log配置logger页签，-Dmask=password(3,8)配置日志脱敏（password之后的第3至8个字符加星*）

#### [logserver-spring-boot-starter](https://gitee.com/xlongwei/logserver-spring-boot-starter)
```xml
<dependency>
    <groupId>com.xlongwei.logserver</groupId>
    <artifactId>logserver-spring-boot-starter</artifactId>
    <version>0.0.3</version>
</dependency>
<dependency>
	<groupId>org.springframework.boot</groupId>
	<artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

-Dlogserver.token=xlongwei
-Dlogserver.remoteHost=192.168.1.99
-Dmanagement.endpoints.web.exposure.include=logserver

logserver:
  remoteHost: 192.168.1.99 #指定logserver
  token: xlongwei #安全校验，需要与logserver的lajax.token一致
management: #需要依赖spring-boot-starter-actuator
  endpoints:
    web:
      exposure:
        include: logserver #开启LogserverEndpoint，让logserver变更日志级别
```

#### 前端日志

1. [lajax](https://github.com/eshengsky/lajax)：var logger = new Lajax(url); logger.info(arg1,...args);
2. [logserver.js](https://log.xlongwei.com/logserver.js)：Lajax.logLevel='info'; Lajax.logServer=false; Lajax.logConsole=true; Lajax.token='xlongwei';
3. [uni-app](https://gitee.com/xlongwei/apidemo/blob/master/common/logserver.js)：var logger = require('../../common/logserver.js').logger; logger.info('onReady index.vue');

```
var logger = new Lajax({
	url:'/lajax',//日志服务器的 URL
	autoLogError:false,//是否自动记录未捕获错误true
	autoLogRejection:false,//是否自动记录Promise错误true
	autoLogAjax:false,//是否自动记录 ajax 请求true
	//logAjaxFilter:function(ajaxUrl, ajaxMethod) {
	//	return false;//ajax 自动记录条件过滤函数true记录false不记录
	//},
	stylize:true,//是否要格式化 console 打印的内容true
	showDesc:false,//是否显示初始化描述信息true
	//customDesc:function(lastUnsend, reqId, idFromServer) {
	//	return 'lajax 前端日志模块加载完成。';
	//},
	interval: 5000,//日志发送到服务端的间隔时间10000毫秒
	maxErrorReq:3 //发送日志请求连续出错的最大次数
});
```

##### 整体设计

设计图：底层使用logback+socket、lajax+http传输日志，后端推荐logback.xml方式，可选starter依赖，前端支持web和uni-app形式，logserver可选使用light-search+lucene创建索引，详细用法见[wiki](https://gitee.com/xlongwei/logserver/wikis)。

![logserver](http://t.xlongwei.com/images/logserver/logserver.png)

演示地址：[https://log.xlongwei.com/](https://log.xlongwei.com/)

![search](http://t.xlongwei.com/images/logserver/search.png)


##### Nginx配置

登录认证见[pass.db](http://api.xlongwei.com/doku.php?id=tools:logstation)生成命令，nginx配置参考[log.conf](https://gitee.com/xlongwei/logserver/blob/master/aliyun/log.conf)。
