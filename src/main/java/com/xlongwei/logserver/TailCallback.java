package com.xlongwei.logserver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.networknt.utility.StringUtils;

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
	private static WebSocketChannel channel;
	private static File logs = new File(ExecUtil.logs);
	private static boolean userTailer = Boolean.getBoolean("userTailer");
	private static BlockingQueue<String> notifyQueue = new LinkedBlockingDeque<>();
	private static boolean notifyQueueStarted = false;
	private static final Logger log = LoggerFactory.getLogger(TailCallback.class);
	
	public static void notify(String txt) {
		if(userTailer || channel==null || StringUtils.isBlank(txt = StringUtils.trimToEmpty(txt))) return;
		boolean offer = notifyQueue.offer(txt);
		if(offer && !notifyQueueStarted) {
			notifyQueueStarted = true;
			PageHandler.scheduler.submit(() -> {
				while(true) {
					String notify = notifyQueue.take();
					Set<WebSocketChannel> peerConnections = channel.getPeerConnections();
					for(WebSocketChannel connection : peerConnections) {
						if(connection.isOpen()) {
							WebSockets.sendText(notify, connection, null);
						}
					}
				}
			});
		}
	}
	
	@Override
	public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
		if(logs.exists()) {
			String tail = ExecUtil.tail(FilenameUtils.getName(ExecUtil.logs), 100);
			WebSockets.sendText(tail, channel, null);
		}
		if(!userTailer) {
			TailCallback.channel = channel;
			return;
		}
		log.info("tailer logs on connect");
		if(tailer == null) {
			if(logs.exists()) {
    			tailer = new Tailer(logs, StandardCharsets.UTF_8, new TailerListenerAdapter() {
					@Override
					public void handle(String line) {
						Set<WebSocketChannel> peerConnections = channel.getPeerConnections();
						int openConnections = 0;
						for(WebSocketChannel connection : peerConnections) {
							if(connection.isOpen()) {
								openConnections++;
								WebSockets.sendText(line, connection, null);
							}
						}
						if(openConnections<=0 && tailer!=null) {
							log.info("tailer stop and end");
							tailer.stop();
							tailer = null;
						}
					}
    			}, 1000, true, false, 4096);
    			PageHandler.scheduler.submit(tailer);
    			log.info("tailer init and start");
			}else {
				log.info("tailer logs not exist: {}", ExecUtil.logs);
			}
		}
		channel.getReceiveSetter().set(new AbstractReceiveListener() {
			@Override
			protected void onCloseMessage(CloseMessage cm, WebSocketChannel channel) {
				log.info("tailer logs on disconnect");
				Set<WebSocketChannel> peerConnections = channel.getPeerConnections();
				int openConnections = 0;
				for(WebSocketChannel connection : peerConnections) {
					if(connection.isOpen()) {
						openConnections++;
					}
				}
				if(tailer!=null && openConnections<=0) {
					tailer.stop();
					tailer = null;
					log.info("tailer stop and end");
				}
			}
		});
		channel.resumeReceives();
	}
	
}
