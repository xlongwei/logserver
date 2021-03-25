package ch.qos.logback.classic.redis;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.net.ReceiverBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.util.Duration;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

public class RedisReceiver extends ReceiverBase implements Runnable {
	Logger log = null;
	boolean pubsub = true;//发送方publish，接收方subscribe
	boolean pushpop = false;//发送方lpush（使用lrange限制长度），接收方rbpop（阻塞）
	int queueSize = 10240;//接受方速度较慢时，缓冲queueSize条日志
	byte[] key = "logserver".getBytes(StandardCharsets.UTF_8);
	String host = "localhost";
	int port = Protocol.DEFAULT_PORT;
	int timeout = Protocol.DEFAULT_TIMEOUT;
	String password = null;
	int db = Protocol.DEFAULT_DATABASE;
	Duration reconnectionDelay = new Duration(10000);
	BlockingQueue<byte[]> blockingQueue = null;
	JedisPool pool = null;
	LoggerContext lc = null;
	ExecutorService es = null;
	ScheduledExecutorService ses = null;
	Runnable takeRun = null, pubsubRun = null, pushpopRun = null;
	Future<?> takeFuture = null, pubsubFuture = null, pushpopFuture = null;
	BinaryJedisPubSub subscribe = new BinaryJedisPubSub() {
		@Override
		public void onMessage(byte[] channel, byte[] message) {
			offer(message);
		}
		@Override
		public void onPMessage(byte[] pattern, byte[] channel, byte[] message) {
			log.info("onPMessage");
		}
		@Override
		public void onSubscribe(byte[] channel, int subscribedChannels) {
			log.info("onSubscribe");
		}
		@Override
		public void onUnsubscribe(byte[] channel, int subscribedChannels) {
			log.info("onUnsubscribe");
		}
		@Override
		public void onPUnsubscribe(byte[] pattern, int subscribedChannels) {
			log.info("onPUnsubscribe");
		}
		@Override
		public void onPSubscribe(byte[] pattern, int subscribedChannels) {
			log.info("onPSubscribe");
		}
	};
	
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

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setDb(int db) {
		this.db = db;
	}

	@Override
	protected boolean shouldStart() {
		return pubsub || pushpop;
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
	
	private void init() {
		blockingQueue = new ArrayBlockingQueue<>(queueSize);
		lc = (LoggerContext) getContext();
		es = lc.getExecutorService();
		ses = lc.getScheduledExecutorService();
		log = lc.getLogger(getClass());
		JedisPoolConfig poolConfig = new JedisPoolConfig();
		poolConfig.setMinIdle(1);
		poolConfig.setTimeBetweenEvictionRunsMillis(0);
		pool = new JedisPool(poolConfig, host, port, timeout, password, db);
	}
	
	@Override
	public void run() {
		init();
		takeFuture = es.submit(takeRun = () -> {
				try {
					log.info("take blockingQueue started");
					while(true) {
						byte[] message = blockingQueue.take();
						ILoggingEvent event = deserialize(message);
						if(event != null) {
							Logger remoteLogger = lc.getLogger(event.getLoggerName());
							if (remoteLogger.isEnabledFor(event.getLevel())) {
								remoteLogger.callAppenders(event);
							}
						}
					}
				}catch(InterruptedException e) {
					log.warn("take blockingQueue interrupted: {} {}", e.getClass().getName(), e.getMessage());
				}
			}
		);
		if(pubsub) {
			pubsubFuture = es.submit(pubsubRun = () -> {
					log.info("subscribe started");
					try(Jedis client = pool.getResource()) {
						client.subscribe(subscribe, key);
					}catch(Exception e) {
						log.warn("subscribe exceptioned: {} {}", e.getClass().getName(), e.getMessage());
					}
				}
			);
		}
		if(pushpop) {
			pushpopFuture = es.submit(pushpopRun = () -> {
					log.info("brpop started");
					try(Jedis client = pool.getResource()) {
						while(true) {
							List<byte[]> list = client.brpop(1000, key);
							if(list!=null && !list.isEmpty()) {
								for(byte[] message : list) {
									offer(message);
								}
							}
						}
					}catch(Exception e) {
						log.warn("brpop exceptioned: {} {}", e.getClass().getName(), e.getMessage());
					}
				}
			);
		}
		ses.scheduleWithFixedDelay(()->{
			if(takeFuture.isDone() || takeFuture.isCancelled()) {
				takeFuture = es.submit(takeRun);
			}
			if(pubsub && (pubsubFuture.isDone() || pubsubFuture.isCancelled())) {
				pubsubFuture = es.submit(pubsubRun);
			}
			if(pushpop && (pushpopFuture.isDone() || pushpopFuture.isCancelled())) {
				pushpopFuture = es.submit(pushpopRun);
			}
		}, 1, reconnectionDelay.getMilliseconds(), TimeUnit.MILLISECONDS);
	}

}
