package com.xlongwei.logserver;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.networknt.body.BodyHandler;
import com.networknt.handler.LightHttpHandler;
import com.networknt.utility.StringUtils;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;

public class LajaxHandler implements LightHttpHandler {
	public static final HttpString ORIGIN = new HttpString("Origin");
	public static final HttpString ACCESS_CONTROL_REQUEST_METHOD = new HttpString("Access-Control-Request-Method");
	public static final HttpString ACCESS_CONTROL_REQUEST_HEADERS = new HttpString("Access-Control-Request-Headers");
	public static final HttpString ACCESS_CONTROL_ALLOW_ORIGIN = new HttpString("Access-Control-Allow-Origin");
	public static final HttpString ACCESS_CONTROL_ALLOW_METHODS = new HttpString("Access-Control-Allow-Methods");
	public static final HttpString ACCESS_CONTROL_ALLOW_HEADERS = new HttpString("Access-Control-Allow-Headers");
	private static Logger log = LoggerFactory.getLogger("lajax");
	private static String token = System.getProperty("lajax.token");
	
	@Override
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		HeaderMap requestHeaders = exchange.getRequestHeaders();
		if(isCoreRequest(requestHeaders)) {
			HeaderMap responseHeaders = exchange.getResponseHeaders();
			responseHeaders.add(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			responseHeaders.add(ACCESS_CONTROL_ALLOW_METHODS, "POST");
	    	responseHeaders.add(ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
	    	if(Methods.OPTIONS.equals(exchange.getRequestMethod())) {
	    		exchange.setStatusCode(HttpURLConnection.HTTP_OK);
	    		return;
	    	}
		}
		if(StringUtils.isBlank(token) || token.equals(exchange.getRequestHeaders().getFirst("X-Request-Token"))) {
			Object body = exchange.getAttachment(BodyHandler.REQUEST_BODY);
			//[{time,level,messages:["{reqId}",arg1,...args],url,agent}]
			try {
				((List)body).forEach(item -> {
					Map map = (Map)item;
					String level = (String)(map).get("level");
					List list = (List)map.get("messages");
					String reqId = list.get(0).toString(), message = list.get(1).toString();
					reqId = reqId.substring(1, reqId.length()-1);
					if("info".equals(level)) {
						log.info("lajax {} {} {}: {}, url={}, agent={}", map.get("time"), level.toUpperCase(), reqId, message, map.get("url"), map.get("agent"));
					}else if("warn".equals(level)) {
						log.warn("lajax {} {} {}: {}, url={}, agent={}", map.get("time"), level.toUpperCase(), reqId, message, map.get("url"), map.get("agent"));
					}else {
						log.error("lajax {} {} {}: {}, url={}, agent={}", map.get("time"), level.toUpperCase(), reqId, message, map.get("url"), map.get("agent"));
					}
				});
			}catch(Exception e) {
				log.debug("lajax fail={}, body={}", e.getMessage(), body);
			}
		}
		exchange.setStatusCode(HttpURLConnection.HTTP_OK);
	}

    public static boolean isCoreRequest(HeaderMap headers) {
        return headers.contains(ORIGIN)
                || headers.contains(ACCESS_CONTROL_REQUEST_HEADERS)
                || headers.contains(ACCESS_CONTROL_REQUEST_METHOD);
    }
    
}
