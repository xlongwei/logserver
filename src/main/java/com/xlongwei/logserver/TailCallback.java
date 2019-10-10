package com.xlongwei.logserver;

import java.io.File;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;

/**
 * 跟踪最新日志，使用WebSocket实时通知浏览器
 * @author xlongwei
 *
 */
public class TailCallback implements WebSocketConnectionCallback {
	private Tailer tailer = null;
	private Logger log = LoggerFactory.getLogger(getClass());
	
	@Override
	public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
		log.info("tailer logs on connect");
		if(tailer == null) {
			File logs = new File(ExecUtil.logs);
			if(logs.exists()) {
    			tailer = new Tailer(logs, StandardCharsets.UTF_8, new TailerListenerAdapter() {
					@Override
					public void handle(String line) {
						if(channel.getPeerConnections().isEmpty()) {
							log.info("tailer stop and end");
							tailer.stop();
							tailer = null;
						}else {
							channel.getPeerConnections().parallelStream().forEach(c -> WebSockets.sendText(line, c, null));
						}
					}
    			}, 1000, true, false, 4096);
    			tailer.run();
    			log.info("tailer init and start");
			}else {
				log.info("tailer logs not exist: "+ExecUtil.logs);
			}
		}
		channel.getReceiveSetter().set(new AbstractReceiveListener() {
			@Override
			protected void onCloseMessage(CloseMessage cm, WebSocketChannel channel) {
				log.info("tailer logs on disconnect");
				if(tailer!=null && channel.getPeerConnections().isEmpty()) {
					tailer.stop();
					tailer = null;
					log.info("tailer stop and end");
				}
			}
		});
		channel.resumeReceives();
	}
	
}
