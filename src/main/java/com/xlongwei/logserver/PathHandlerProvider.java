package com.xlongwei.logserver;

import static io.undertow.Handlers.path;
import static io.undertow.Handlers.resource;
import static io.undertow.Handlers.websocket;

import java.io.File;

import com.networknt.handler.HandlerProvider;

import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.FileResourceManager;

/**
 * 接收所有请求，路由部分请求给tail、log处理
 * @author xlongwei
 */
public class PathHandlerProvider implements HandlerProvider {

	@Override
	public HttpHandler getHandler() {
		return path()
				.addExactPath("/tail", websocket(new TailCallback()))
				.addExactPath("/lajax", new LajaxHandler())
				.addExactPath("/log", new PageHandler())
				.addPrefixPath("/logs", resource(new FileResourceManager(new File(ExecUtil.dir), 1, false)))
				.addPrefixPath("/", resource(new ClassPathResourceManager(PathHandlerProvider.class.getClassLoader(), "static")))
				;
	}

}
