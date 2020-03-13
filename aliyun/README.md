## aliyun-sdk

  - aliyun-java-sdk-cms，支持上报自定义监控数据，用于上报java应用的gc、threads等数据
  - aliyun-java-sdk-alidns，支持修改DNS解析，用于https证书生成时设置TXT record验证域名

## 上报自定义监控数据

  - check_app.sh脚本，云主机目录为/soft/shells，其他目录时需要修改相关路径
  - 使用crontab定时执行脚本，[参考博客](https://xlongwei.com/detail/19122523)

	*/1 * * * * sh /soft/shells/check_apps.sh >> /var/log/cms_check.log

## https证书生成

  - 配置aliyun密钥，vi start.sh打开注释并设置accessKeyId、secret，密钥可选保存到文件/etc/aliyun.secret

	ENV_OPS="accessKeyId=7sTaWT0zAVYmtxlq regionId=cn-hangzhou secret=`cat /etc/aliyun.secret`"
  
  - 如果需要自动修改DNS解析，打开注释并设置domainName、recordId，[参考文档](https://help.aliyun.com/document_detail/29776.html?spm=a2c4g.11186623.6.653.4e891cebBliL2r)

	ENV_OPS="domainName=xlongwei.com recordId=4012091293697024"
