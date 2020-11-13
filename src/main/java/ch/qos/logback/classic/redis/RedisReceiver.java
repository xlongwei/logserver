package ch.qos.logback.classic.redis;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.net.ReceiverBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.util.Duration;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Protocol;

public class RedisReceiver extends ReceiverBase implements Runnable {

	boolean pubsub = true;//发送方publish，接收方subscribe
	boolean pushpop = false;//发送方lpush（使用lrange限制长度），接收方rbpop（阻塞）
	int queueSize = 10240;//接受方速度较慢时，缓冲queueSize条日志
	byte[] key = "logserver".getBytes(StandardCharsets.UTF_8);
	String host = "localhost";
	int port = Protocol.DEFAULT_PORT;
	Duration reconnectionDelay = new Duration(10000);
	BlockingQueue<byte[]> blockingQueue = null;
	JedisPool pool = null;
	Method returnBrokenResource = null;
	ExecutorService es = null;

	public void setPool(JedisPool pool) {
		this.pool = pool;
	}

	public void setPubsub(boolean pubsub) {
		this.pubsub = pubsub;
	}

	public void setPushpop(boolean pushpop) {
		this.pushpop = pushpop;
	}

	public void setQueueSize(int queueSize) {
		this.queueSize = queueSize;
	}

	public void setKey(String key) {
		this.key = key.getBytes(StandardCharsets.UTF_8);
	}
	
	public void setHost(String host) {
		this.host = host;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public void setReconnectionDelay(Duration reconnectionDelay) {
		this.reconnectionDelay = reconnectionDelay;
	}

	@Override
	protected boolean shouldStart() {
		return true;
	}

	@Override
	protected void onStop() {
		if(pool != null) {
			pool.destroy();
		}
	}
	
	private static ILoggingEvent deserialize(byte[] bs) {
		try (ByteArrayInputStream bais = new ByteArrayInputStream(bs);
				ObjectInputStream ois = new ObjectInputStream(bais)) {
			Object obj = ois.readObject();
			return obj instanceof ILoggingEvent ? (ILoggingEvent) obj : null;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	protected Runnable getRunnableTask() {
		return this;
	}
	
	private void offer(byte[] message) {
		if(message != null) {
			boolean offer = blockingQueue.offer(message);
			if(offer == false) {
				blockingQueue.poll();
				blockingQueue.offer(message);
			}
		}
	}
	
	private void returnBrokenResource(Jedis client) {
//		if(returnBrokenResource==null) {
//			try {
//				returnBrokenResource = JedisPool.class.getDeclaredMethod("returnBrokenResource", Jedis.class);
//				returnBrokenResource.setAccessible(true);
//			}catch(Exception e) {
////				System.err.println("fail to get method returnBrokenResource: "+e.getMessage());
//			}
//		}
		try {
			//针对jedis不同版本，可以直接调用close或returnBrokenResource方法，则注释掉反射代码即可
//			returnBrokenResource.invoke(pool, client);
			client.close();
//			pool.returnBrokenResource(client);			
		}catch(Exception e) {
//			System.err.println("fail to returnBrokenResource: "+e.getMessage());
		}
		try{
			Thread.sleep(reconnectionDelay.getMilliseconds());
		}catch(InterruptedException e) {
//			System.err.println("interrupt returnBrokenResource: "+e.getMessage());
		}
	}
	
	private synchronized Jedis getResource(Jedis client) {
		if(client != null && client.isConnected()) {
			return client;
		}
		for(int i=0; i < 3; i++) {
			try {
				Future<Jedis> future = es.submit(() -> {
					if(pool == null) {
						pool = new JedisPool(host, port);
					}
					return pool.getResource();
				});
				client = future.get(3, TimeUnit.SECONDS);
				return client;
			}catch(Exception e) {
//				System.err.println("fail to getResource: "+e.getMessage());
				returnBrokenResource(client);
			}
		}
		return null;
	}
	
	@Override
	public void run() {
		if(!pubsub && !pushpop) {
			return;
		}
		blockingQueue = new ArrayBlockingQueue<>(queueSize);
		LoggerContext lc = (LoggerContext) getContext();
		es = lc.getExecutorService();
		es.submit(() -> {
				while(true) {
					try {
						byte[] message = blockingQueue.take();
						ILoggingEvent event = deserialize(message);
						if(event != null) {
							Logger remoteLogger = lc.getLogger(event.getLoggerName());
			                if (remoteLogger.isEnabledFor(event.getLevel())) {
			                    remoteLogger.callAppenders(event);
			                }
						}
					}catch(InterruptedException e) {
						
					}
				}
			}
		);
		if(pubsub) {
			es.submit(() -> {
					BinaryJedisPubSub subscribe = new BinaryJedisPubSub() {
						@Override
						public void onMessage(byte[] channel, byte[] message) {
							offer(message);
						}
						@Override
						public void onPMessage(byte[] pattern, byte[] channel, byte[] message) {}
						@Override
						public void onSubscribe(byte[] channel, int subscribedChannels) {}
						@Override
						public void onUnsubscribe(byte[] channel, int subscribedChannels) {}
						@Override
						public void onPUnsubscribe(byte[] pattern, int subscribedChannels) {}
						@Override
						public void onPSubscribe(byte[] pattern, int subscribedChannels) {}
					};
					Jedis client = null;
					while(true) {
						try {
							client = getResource(client);
							client.subscribe(subscribe, key);
						}catch(Exception e) {
//							System.err.println("fail to subscribe: "+e.getMessage());
							returnBrokenResource(client);
						}
					}
				}
			);
		}
		if(pushpop) {
			es.submit(() -> {
					Jedis client = null;
					while(true) {
						try {
							client = getResource(client);
							List<byte[]> list = client.brpop(1000, key);
							if(list!=null && !list.isEmpty()) {
								for(byte[] message : list) {
									offer(message);
								}
							}
						}catch(Exception e) {
//							System.err.println("fail to brpop: "+e.getMessage());
							returnBrokenResource(client);
						}
					}
				}
			);
		}
	}

}
