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
	private static final Logger log = LoggerFactory.getLogger("lajax");
	public static final String token = System.getProperty("lajax.token");
	private static final String LAJAX_LOG_FORMAT = "lajax {} {} {}: {}, url={}, agent={}";
	
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
					String level = ((String)(map).get("level")).toUpperCase();
					List list = (List)map.get("messages");
					String reqId = list.get(0).toString();
					String message = list.get(1).toString();
					reqId = reqId.substring(1, reqId.length()-1);
					Object time = map.get("time");
					Object url = map.get("url");
					Object agent = map.get("agent");
					if("info".equals(level)) {
						log.info(LAJAX_LOG_FORMAT, time, level, reqId, message, url, agent);
					}else if("warn".equals(level)) {
						log.warn(LAJAX_LOG_FORMAT, time, level, reqId, message, url, agent);
					}else {
						log.error(LAJAX_LOG_FORMAT, time, level, reqId, message, url, agent);
					}
				});
			}catch(Exception e) {
				log.debug("lajax fail={}, msg={}, body={}", e.getClass().getSimpleName(), e.getMessage(), body);
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
