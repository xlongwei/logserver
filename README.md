## apidoc

##### 使用方法
1. 创建数据库：apidoc（字符集utf8mb4或utf8），导入脚本：api-web/db/sosoapi-1.0.0.sql
2. 修改配置：api-web项目里的 filter-dev-master.properties、mail-cfg.properties
3. 构建项目：mvn install，mvn compile resources:resources war:exploded -f api-web/pom.xml
4. 部署到tomcat：&lt;Context docBase="apidoc/api-web/target/apidoc" path="/apidoc" reloadable="true"/&gt;
5. 访问：[http://localhost:8080/apidoc/](http://localhost:8080/apidoc/)，登录：admin@qq.com，密码：123456
6. 线上部署：sh deploy.sh，mvn compile resources:resources war:exploded -P env-aliyun-master -f api-web/pom.xml

##### 优化特性
1. 接口协议优先顺序：接口Inter > 文档ApiDoc > 网址Url，模拟时会忽略ApiDoc已有的scheme、host、basePath
2. 支持接口模拟：可以给请求参数指定响应，也可以js代码动态生成响应，[演示](https://api.xlongwei.com/apidoc/pass/apidoc/demo.htm?doc=1)，[模拟](https://api.xlongwei.com/apidoc/pass/apidoc/demo.htm?doc=1&mock=true)，模拟失败时响应schema结构和注释信息
3. 支持生成knife4j-front需要的group.json文件，[演示](https://api.xlongwei.com/swagger/doc.html?doc=1)，[模拟](https://api.xlongwei.com/swagger/doc.html?doc=1&mock=true)

##### 在线演示
演示地址：
[apidoc](https://api.xlongwei.com/apidoc/)
[knife4j](https://api.xlongwei.com/swagger/doc.html)

![mock](http://t.xlongwei.com/images/apidoc/mock.png)

##### 常见问题
 - 数据库字符集：mysql高版本支持utf8mb4则优先使用，低版本使用utf8也行。

spring-mybatis.xml，连接池使用了druid，因为原来的bonecp在低版本mysql时报错。

 - 发送邮件失败：

java.lang.NoSuchMethodError: com.sun.mail.util.TraceInputStream，org.apache.commons.mail.EmailException: Sending the email to the following server failed。解决办法：base-mail/pom.xml，修改commons-email为1.5版本。

##### 联系合作
  - QQ群：957806743，钉钉群：21975907
  - Email：admin@xlongwei.com
