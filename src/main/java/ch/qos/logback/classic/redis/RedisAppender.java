package ch.qos.logback.classic.redis;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.LoggingEventVO;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.util.Duration;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

public class RedisAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
	Logger log = LoggerFactory.getLogger(getClass());
	boolean pubsub = true;//发送方publish，接收方subscribe
	boolean pushpop = false;//发送方lpush（使用lrange限制长度），接收方rbpop（阻塞）
	int queueSize = 10240;//接收方异常时，缓存queueSize条日志
	byte[] key = "logserver".getBytes(StandardCharsets.UTF_8);
	String host = "localhost";
	int port = Protocol.DEFAULT_PORT;
	int timeout = Protocol.DEFAULT_TIMEOUT;
	String password = null;
	int db = Protocol.DEFAULT_DATABASE;
	Duration reconnectionDelay = new Duration(10000);
	BlockingQueue<byte[]> blockingQueue = null;
	JedisPool pool = null;
	Boolean ltrim = null;

	@Override
	protected void append(ILoggingEvent event) {
		byte[] message = serialize(event);
		offer(message);
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
	
	private static byte[] serialize(ILoggingEvent event) {
		Serializable obj = null;
		if (event instanceof Serializable) {
			obj = (Serializable) event;
		} else if (event instanceof LoggingEvent) {
			obj = LoggingEventVO.build(event);
		}
		if (obj == null) {
			return null;
		}
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
				ObjectOutputStream oos = new ObjectOutputStream(baos)) {
			oos.writeObject(obj);
			oos.flush();
			return baos.toByteArray();
		} catch (Exception e) {
			return null;
		}
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
	
	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setDb(int db) {
		this.db = db;
	}
	
	private void init() {
		blockingQueue = new ArrayBlockingQueue<>(queueSize);
		JedisPoolConfig poolConfig = new JedisPoolConfig();
		poolConfig.setMinIdle(1);
		poolConfig.setTimeBetweenEvictionRunsMillis(0);
		pool = new JedisPool(poolConfig, host, port, timeout, password, db);
	}

	@Override
	public void start() {
		super.start();
		if(!pubsub && !pushpop) {
			return;
		}
		init();
		LoggerContext lc = (LoggerContext) getContext();
		ScheduledExecutorService ses = lc.getScheduledExecutorService();
		ses.scheduleWithFixedDelay(() -> {
				try(Jedis client = pool.getResource()){
					int count = 0, threshhold = queueSize / 10;
					while(true) {
						byte[] message = blockingQueue.take();
						if(pubsub) {
							client.publish(key, message);
						}
						if(pushpop) {
							client.lpush(key, message);
							count++;
							if(queueSize > 0 && count > threshhold) {
								if(ltrim == null) {
									try {
										client.ltrim(key, 0, queueSize);
										ltrim = Boolean.TRUE;
									}catch(Throwable t) {
										log.warn("ltrim exceptioned: {} {}", t.getClass().getName(), t.getMessage());
										ltrim = Boolean.FALSE;
									}
								}else if(ltrim.booleanValue()){
									client.ltrim(key, 0, queueSize);
								}
								count = 0;
							}
						}
					}
				}catch(Exception e) {
					log.warn("append exceptioned: {} {}", e.getClass().getName(), e.getMessage());
				}
			}, 1, reconnectionDelay.getMilliseconds(), TimeUnit.MILLISECONDS
		);
	}

	@Override
	public void stop() {
		super.stop();
		if(pool != null) {
			pool.destroy();
		}
	}

}
