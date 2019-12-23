hostPort=localhost:9880
clients=1
seconds=2
serviceUrl=http://$hostPort

echo "$serviceUrl/log?type=list" > /tmp/urls.txt
http_load -parallel $clients -seconds $seconds /tmp/urls.txt
