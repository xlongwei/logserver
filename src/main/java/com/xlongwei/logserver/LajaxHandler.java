package com.xlongwei.logserver;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.networknt.body.BodyHandler;
import com.networknt.cors.CorsHeaders;
import com.networknt.cors.CorsUtil;
import com.networknt.handler.LightHttpHandler;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;

public class LajaxHandler implements LightHttpHandler {
	private static Logger log = LoggerFactory.getLogger("lajax");
	
	@Override
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		if(!CorsUtil.isPreflightedRequest(exchange)) {
			Object body = exchange.getAttachment(BodyHandler.REQUEST_BODY);
			//[{time,level,messages:["{reqId}",arg1,...args],url,agent}]
			try {
				((List)body).forEach(item -> {
					String level = (String)((Map)item).get("level");
					if("info".equals(level)) {
						log.info("{}", item);
					}else if("warn".equals(level)) {
						log.warn("{}", item);
					}else {
						log.error("{}", item);
					}
				});
			}catch(Exception e) {
				log.debug("{}, {}", e.getMessage(), body);
			}
		}
		setCorsHeaders(exchange);
		exchange.setStatusCode(200);
	}

	void setCorsHeaders(HttpServerExchange exchange) {
		HeaderMap responseHeaders = exchange.getResponseHeaders();
		responseHeaders.add(CorsHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		responseHeaders.add(CorsHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST");
    	responseHeaders.add(CorsHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
	}
}
