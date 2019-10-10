## logserver

##### 项目简介
使用logback和light-4j构建的简单日志服务，参考项目[logbackserver](https://gitee.com/xlongwei/logbackserver)和[light4j](https://gitee.com/xlongwei/light4j)。

##### 本地测试

1. 项目构建：mvn package dependency:copy-dependencies -DoutputDirectory=target
2. 运行服务：start.bat，打开首页[index](http://localhost:9880/index.html)，点开[tail](http://localhost:9880/tail.html)跟踪日志
3. client测试：client.bat，输入测试内容，浏览器会输出最新日志

##### 线上部署

1. 项目打包：sh start.sh deploy
2. 运行服务：sh start.sh start
3. 其他项目的日志配置参考client.xml

##### 演示图

![index](http://t.xlongwei.com/images/logserver/index.png)

![tail](http://t.xlongwei.com/images/logserver/tail.png)

![page](http://t.xlongwei.com/images/logserver/page.png)
