package com.xlongwei.logserver;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.alidns.model.v20150109.DescribeDomainRecordsRequest;
import com.aliyuncs.alidns.model.v20150109.DescribeDomainRecordsResponse;
import com.aliyuncs.alidns.model.v20150109.DescribeDomainRecordsResponse.Record;
import com.aliyuncs.alidns.model.v20150109.UpdateDomainRecordRequest;
import com.aliyuncs.http.FormatType;
import com.aliyuncs.http.HttpClientConfig;
import com.aliyuncs.http.HttpClientFactory;
import com.aliyuncs.http.HttpRequest;
import com.aliyuncs.http.HttpResponse;
import com.aliyuncs.http.IHttpClient;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.body.BodyHandler;
import com.networknt.config.Config;
import com.networknt.handler.LightHttpHandler;
import com.networknt.utility.StringUtils;
import com.networknt.utility.Tuple;
import com.networknt.utility.Util;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.MimeMappings;

/**
 * 分页日志文件，响应json数据
 * @author xlongwei
 *
 */
public class PageHandler implements LightHttpHandler {
	public static final ScheduledThreadPoolExecutor scheduler;
	public static IHttpClient httpClient;
	private ObjectMapper mapper = Config.getInstance().getMapper();
	private String json = MimeMappings.DEFAULT.getMimeType("json");
	private static final Logger log = LoggerFactory.getLogger(PageHandler.class);
	public static IAcsClient client = null;
	private IClientProfile profile = null;
	private boolean metricEnabled = false, dnsEnabled = false;
	private String domainName = ExecUtil.firstNotBlank(System.getenv("domainName"), "xlongwei.com");
	private String recordId = ExecUtil.firstNotBlank(System.getenv("recordId"), "4012091293697024");
	private String lightSearch = System.getProperty("light-search", "http://localhost:9200");
	public static final LinkedList<String[]> metrics = new LinkedList<>();
	public static final Map<String, Tuple<AtomicInteger, AtomicInteger>> metricsMap = new HashMap<>();
	private String wellKnown = ExecUtil.firstNotBlank(System.getProperty("wellKnown"), "/soft/statics")+"/.well-known/acme-challenge";
	
	static {
		//调整logback线程个数=3+client个数，其中3=socketAccept+tailer+scheduleWithFixedDelay
		LoggerContext lc = (LoggerContext)LoggerFactory.getILoggerFactory();
		scheduler = (ScheduledThreadPoolExecutor)lc.getScheduledExecutorService();
		//ScheduledThreadPoolExecutor不会按需创建新线程，logback内部的submit、execute可能为耗时任务，因此LogbackScheduler使用独立的线程池来执行耗时任务
		scheduler.setCorePoolSize(Math.max(1, Util.parseInteger(System.getenv("logbackThreads"))));
	}
	
	public PageHandler() {
		String accessKeyId = System.getenv("accessKeyId"), regionId = ExecUtil.firstNotBlank(System.getenv("regionId"), "cn-hangzhou"), secret = System.getenv("secret");
		boolean configNonBlank = StringUtils.isNotBlank(accessKeyId) && StringUtils.isNotBlank(secret)
				&& StringUtils.isNotBlank(regionId);
		metricEnabled = configNonBlank && "true".equalsIgnoreCase(System.getenv("metricEnabled"));
		dnsEnabled = configNonBlank && !"false".equalsIgnoreCase(System.getenv("dnsEnabled"));
		log.info("accessKeyId={}, metricEnabled={}, regionId={}, recordId={}, dnsEnabled={}", accessKeyId, metricEnabled, regionId, recordId, dnsEnabled);
		profile = DefaultProfile.getProfile(regionId, accessKeyId, secret);
		profile.setCloseTrace(true);// 关闭opentracing但是依赖不能去除
		HttpClientConfig config = profile.getHttpClientConfig();
		config.setClientType(com.aliyuncs.http.HttpClientType.Compatible);
		config.setCompatibleMode(true);
		config.setHostnameVerifier(new HostnameVerifier() {
			@Override
			public boolean verify(String hostname, SSLSession session) {
				return true;
			}
		});
		if (configNonBlank) {
			client = new DefaultAcsClient(profile);
			httpClient = ((DefaultAcsClient) client).getHttpClient();
		} else {
			httpClient = HttpClientFactory.buildClient(profile);
		}
		if(metricEnabled){
			//每15秒上报一次统计数据，每4个小时清理一下统计数据
			scheduler.scheduleWithFixedDelay(Alicms::putCustomMetrics, 15, 15, TimeUnit.SECONDS);
			Calendar calendar = Calendar.getInstance();
			long minuteOfHour = TimeUnit.HOURS.toMinutes(1);
			long minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY)*minuteOfHour+calendar.get(Calendar.MINUTE);
			long range = 4*minuteOfHour;
			long minuteToWait = range - (minuteOfDay%range);
			log.info("metrics map wait {} minutes to clear, client={}", minuteToWait, client);
			scheduler.scheduleWithFixedDelay(metricsMap::clear, minuteToWait, range, TimeUnit.MINUTES);
		}
	}

	

	@Override
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		String type = getParam(exchange, "type");
		String response = null;
		if("list".equals(type)) {
			response = list(exchange);
		}else if("page".equals(type)) {
			response = page(exchange);
		}else if("metric".equals(type)) {
			response = metric(exchange);
		}else if("https".equals(type)) {
			response = https(exchange);
		}else if("alidns".equals(type)){
			response = alidns(exchange);
		}else if("regist".equals(type)){
			response = regist(exchange);
		} else if ("props".equals(type)) {
			String key = getParam(exchange, "key"),
					value = StringUtils.isBlank(key) || !"files,logger".contains(key) ? ""
							: System.getProperty(key).replaceAll("@[^,]+", "@");
			response = "{\"" + key + "\":\"" + value + "\"}";
		}else if("loggers".equals(type)){
			response = loggers(exchange);
		}else {
			response = logger(exchange);
		}
		exchange.setStatusCode(200);
		exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, json);
		exchange.getResponseSender().send(StringUtils.trimToEmpty(response));
	}

	private String loggers(HttpServerExchange exchange) throws Exception {
		String url = getParam(exchange, "appName");
		if (StringUtils.isNotBlank(url)) {
			String[] split = System.getProperty("logger", "").split("[,]");
			for (String nameUrl : split) {
				String[] pair = nameUrl.split(("[@]"));
				if (url.equals(pair[0])) {
					url = pair[1];
					break;
				}
			}
		}
		if (StringUtils.isNotBlank(url) && url.startsWith("http")) {
			Map<String, String> body = new HashMap<>();
			body.put("logger", getParam(exchange, "logger"));
			body.put("level", getParam(exchange, "level"));
			body.put("token", getParam(exchange, "token"));
			if (StringUtils.isNotBlank(body.get("level")) && StringUtils.isNotBlank(LajaxHandler.token)
					&& !LajaxHandler.token.equals(body.get("token"))) {
				body.put("level", StringUtils.EMPTY); // logserver统一控制权限，apidoc通过getParameter获取参数
			}
			String bodyString = Config.getInstance().getMapper().writeValueAsString(body);
			HttpRequest request = new HttpRequest(url + "?logger=" + body.get("logger") + "&level=" + body.get("level")
					+ "&token=" + body.get("token"));
			request.setSysMethod(MethodType.POST);
			request.setHttpContent(bodyString.getBytes(CharEncoding.UTF_8), CharEncoding.UTF_8, FormatType.JSON);
			HttpResponse response = httpClient.syncInvoke(request);
			return response.getHttpContentString();
		} else {
			return logger(exchange);
		}
	}

	private String logger(HttpServerExchange exchange) throws Exception {
		LoggerContext lc = (LoggerContext)LoggerFactory.getILoggerFactory();
		String loggerName = getParam(exchange, "logger");
		List<ch.qos.logback.classic.Logger> loggers = null;
		if(StringUtils.isNotBlank(loggerName)) {
			ch.qos.logback.classic.Logger logger = lc.getLogger(loggerName);
			if(logger != null) {
				loggers = Arrays.asList(logger);
				String levelName = getParam(exchange, "level");
				if (StringUtils.isNotBlank(levelName) && (StringUtils.isBlank(LajaxHandler.token)
						|| LajaxHandler.token.equals(getParam(exchange, "token")))) {
					Level level = Level.toLevel(levelName, null);
					log.warn("change logger:{} level from:{} to:{}", logger.getName(), logger.getLevel(), level);
					logger.setLevel(level);
				}
			}
		}
		if(loggers == null) {
			loggers = lc.getLoggerList();
		}
		log.info("check logger level, loggers:{}", loggers.size());
		List<Map<String, String>> list = loggers.stream().sorted((a, b) -> a.getName().compareTo(b.getName()))
				.map(logger -> {
					Map<String, String> map = new HashMap<>();
					map.put("logger", logger.getName());
					map.put("level", Objects.toString(logger.getLevel(), ""));
					return map;
				}).collect(Collectors.toList());
		Map<String, Object> map = new HashMap<>();
		map.put("loggers", list);
		return mapper.writeValueAsString(map);
	}

	@SuppressWarnings({ "rawtypes" })
	private String list(HttpServerExchange exchange) throws Exception {
		String search = getParam(exchange, "search");
		List<String> list = null;
		if (Boolean.getBoolean("useSearch")) {
			list = new ArrayList<>();
			String url = lightSearch + "/service/logserver/list" + "?search=" + Util.urlEncode(search);
			HttpRequest request = new HttpRequest(url);
			request.setSysMethod(MethodType.POST);
			HttpResponse response = httpClient.syncInvoke(request);
			String string = response.getHttpContentString();
			Map map = mapper.readValue(string, Map.class);
			Object object = map.get("list");
			if (object != null && object instanceof List) {
				List days = (List) object;
				String logs = FilenameUtils.getName(ExecUtil.logs);
				String today = LocalDate.now().toString();
				for (Object day : days) {
					String dayString = day.toString();
					if (today.equals(dayString)) {
						list.add(logs);
					} else {
						list.add(logs + "." + dayString);
					}
				}
			}
		} else {
			list = ExecUtil.list(search);
		}
		log.info("page logs list: {}", search);
		return mapper.writeValueAsString(list);
	}

	private String page(HttpServerExchange exchange) throws Exception {
		String search = getParam(exchange, "search");
		String logs = getParam(exchange, "log");
		Pager pager = new Pager();
		pager.setPageSize(getParam(exchange, "pageSize"), 100);
		int totalRows = Util.parseInteger(getParam(exchange, "totalRows"));
		if(totalRows < 1) {
			totalRows = ExecUtil.count(logs);
		}
		pager.init(totalRows);
		pager.page(Util.parseInteger(getParam(exchange, "currentPage")));
		//点击尾页可以刷新
		if(pager.getTotalPages()>1 && pager.getCurrentPage()==pager.getTotalPages()) {
			pager.init(ExecUtil.count(logs));
		}
		String page = ExecUtil.page(logs, pager.getStartRow(), pager.getEndRow());
		pager.setProperties(page);
		//搜索search所在页码列表
		if(StringUtils.isNotBlank(search)) {
			List<Integer[]> pages = pages(logs, search, pager.getPageSize());
			pager.setOthers(pages);
		}
		log.info("page logs page: {}, {}", logs, pager.getCurrentPage());
		return mapper.writeValueAsString(pager);
	}

	@SuppressWarnings({ "rawtypes" })
	private List<Integer[]> pages(String logs, String search, int pageSize) throws Exception {
		if (Boolean.getBoolean("useSearch")) {
			String url = lightSearch + "/service/logserver/pages?logs=" + logs + "&search=" + Util.urlEncode(search)
					+ "&pageSize=" + pageSize;
			HttpRequest request = new HttpRequest(url);
			request.setSysMethod(MethodType.POST);
			HttpResponse response = httpClient.syncInvoke(request);
			String string = response.getHttpContentString();
			Map map = mapper.readValue(string, Map.class);
			Object object = map.get("pages");
			if (object != null && object instanceof List) {
				List<Integer[]> pages = new LinkedList<>();
				List list = (List) object;
				for (Object item : list) {
					List arr = (List) item;
					pages.add(new Integer[] { (Integer) arr.get(0), (Integer) arr.get(1) });
				}
				return pages;
			}
			return Collections.emptyList();
		} else {
			List<Integer> lines = ExecUtil.lines(logs, search);
			return ExecUtil.pages(lines, pageSize);
		}
	}

	private String metric(HttpServerExchange exchange) throws JsonProcessingException {
		String metricName = getParam(exchange, "metricName");
		String value = getParam(exchange, "value");
		String appName = getParam(exchange, "appName");
		if(StringUtils.isNotBlank(metricName) && StringUtils.isNotBlank(appName) && StringUtils.isNotBlank(value)) {
			if(metricEnabled){
				String[] metric = new String[] {metricName, value, appName};
				metrics.offerLast(metric);
			}
			return "{\"metric\":"+metricEnabled+"}";
		}else {
			Map<String, Integer> map = new TreeMap<>();
			for(Entry<String, Tuple<AtomicInteger, AtomicInteger>> entry : metricsMap.entrySet()) {
				String key = entry.getKey();
				Tuple<AtomicInteger, AtomicInteger> tuple = entry.getValue();
				Integer avg = tuple.second.get() / tuple.first.get();
				map.put(key, avg);
			}
			return mapper.writeValueAsString(map);
		}
	}
	
	private String https(HttpServerExchange exchange) throws Exception {
		String step = getParam(exchange, "step"), param = getParam(exchange, "param");
		if(StringUtils.isNotBlank(step)) {
			String cmd = null, data = null;
			switch(step) {
			case "validateAccount":
				if(!new File(ExecUtil.cert, "account.key").exists()) {
					ExecUtil.exec(ExecUtil.cert, CommandLine.parse("openssl genrsa 4096 > account.key"));
				}
				cmd = "openssl rsa -in account.key -pubout";
				data = ExecUtil.exec(ExecUtil.cert, CommandLine.parse(cmd));
				data = data.substring(data.indexOf('-'));
				break;
			case "validateCSR":
				if(!new File(ExecUtil.cert, "domain.key").exists()) {
					ExecUtil.exec(ExecUtil.cert, CommandLine.parse("openssl genrsa 4096 > domain.key"));
				}
				if(StringUtils.isNotBlank(param)) {
					StringBuilder sb = new StringBuilder("#! /bin/bash\nopenssl req -new -sha256 -key domain.key -subj \"/\" -reqexts SAN -config <(cat /etc/pki/tls/openssl.cnf <(printf \"\\n[SAN]\\nsubjectAltName=");
					for(String domain : param.split("[,;]")) {
						if(StringUtils.isBlank(domain=domain.trim())){
							continue;
						}
						sb.append("DNS:").append(domain).append(",");
					}
					sb.deleteCharAt(sb.length()-1).append("\"))");
					File csr = new File(ExecUtil.cert, "csr.sh");
					FileUtils.writeStringToFile(csr, sb.toString(), StandardCharsets.UTF_8);
					ExecUtil.exec(ExecUtil.cert, CommandLine.parse("chmod +x ./csr.sh"));
					data = ExecUtil.exec(ExecUtil.cert, CommandLine.parse("bash csr.sh"));
				}
				break;
			case "signApiRequests":
				if(StringUtils.isNotBlank(param)) {
					if(param.matches("^(PRIV_KEY=\\./)(account|domain)\\.key; echo -n .*( | openssl dgst -sha256 -hex -sign \\$PRIV_KEY)$")) {
						data = runtime(param);
					}else {
						log.info("bad param: {}", param);
					}
				}
				break;
			case "serveThisContent":
				if(StringUtils.isNotBlank(param)) {
					int dot = param.indexOf('.');
					if(dot>0) {
						File file = new File(wellKnown, param.substring(0, dot));
						FileUtils.writeStringToFile(file, param, StandardCharsets.UTF_8);
						data = String.valueOf(file.exists());
					}
				}
				break;
			case "alidns":
				if(StringUtils.isNotBlank(param)) {
					data = alidns(param);
				}
				break;
			default:
				break;
			}
			if(data!=null && StringUtils.isNotBlank(data)) {
				data = data.replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\r");
				return "{\"data\":\""+data+"\"}";
			}
		}
		return null;
	}
	
	private String alidns(HttpServerExchange exchange) {
		String recordId = getParam(exchange, "recordId"), ip = getParam(exchange, "ip");
		if (dnsEnabled && StringUtils.isNotBlank(recordId)
				&& (StringUtils.isBlank(ip) || StringUtils.isBlank(LajaxHandler.token)
						|| LajaxHandler.token.equals(exchange.getRequestHeaders().getFirst("X-Request-Token")))) {
			try {
				DescribeDomainRecordsRequest query = new DescribeDomainRecordsRequest();
				query.setSysRegionId(profile.getRegionId());
				query.setDomainName(domainName);
				DescribeDomainRecordsResponse response = client.getAcsResponse(query);
				List<Record> records = response.getDomainRecords();
				Record record = records.stream().filter(r -> recordId.equals(r.getRecordId())).findFirst().orElse(null);
				String value = record==null ? "false" : record.getValue();
				if(record!=null && StringUtils.isNotBlank(ip) && !ip.equals(value)) {
			        UpdateDomainRecordRequest update = new UpdateDomainRecordRequest();
			        update.setSysRegionId(profile.getRegionId());
			        update.setRecordId(recordId);
			        update.setRR(record.getRR());
			        update.setType(record.getType());
			        update.setValue(ip);
			        client.getAcsResponse(update);
			        return value + " => "+ip;
				}else {
					return value;
				}
			}catch(Exception e) {
				log.warn("alidns fail: {}", e.getMessage());
				return "false: " + e.getMessage();
			}
		}
		return null;
	}
	
	@SuppressWarnings("rawtypes")
	private String regist(HttpServerExchange exchange) throws JsonProcessingException {
		String name = getParam(exchange, "name");
		boolean regist = false;
		if(StringUtils.isNotBlank(name)) {
			Map body = (Map)exchange.getAttachment(BodyHandler.REQUEST_BODY);
			String token = Objects.toString(body.get("token"), null), url = Objects.toString(body.get("url"), null);
			if(StringUtils.isNotBlank(url) && (StringUtils.isBlank(LajaxHandler.token) || LajaxHandler.token.equals(token))) {
				String logger = System.getProperty("logger");
				String pair = name+"@"+url;
				if(StringUtils.isNotBlank(logger)) {
					if(!logger.contains(pair)) {
						logger += "," + pair;
					}
				}else {
					logger = pair;
				}
				System.setProperty("logger", logger);
				regist = true;
			}
			log.warn("{}={} regist={}", name, url, regist);
		}
		return mapper.writeValueAsString(Collections.singletonMap("regist", regist));
	}
	
	private String alidns(String value) {
		if(dnsEnabled==false) {
			log.info("dnsEnabled={}", dnsEnabled);
			return "false";
		}
		try {
			if("true".equals(value) || value.startsWith("false")) {
				DescribeDomainRecordsRequest request = new DescribeDomainRecordsRequest();
		        request.setSysRegionId(profile.getRegionId());
		        request.setDomainName(domainName);
		        DescribeDomainRecordsResponse response = client.getAcsResponse(request);
		        List<Record> records = response.getDomainRecords();
		        Optional<String> record = records.stream().filter(r -> recordId.equals(r.getRecordId())).map(Record::getValue).findFirst();
		        return record.orElse("false");
			}else {
		        UpdateDomainRecordRequest request = new UpdateDomainRecordRequest();
		        request.setSysRegionId(profile.getRegionId());
		        request.setRecordId(recordId);
		        request.setRR("_acme-challenge");
		        request.setType("TXT");
		        request.setValue(value);
		        client.getAcsResponse(request);
		        return "true";
			}
		}catch(Exception e) {
			log.info("alidns fail: {}", e.getMessage());
			return "false: " + e.getMessage();
		}
	}

	private String runtime(String cmd) {
		String data = null;
		try{
			log.info("exec cmd: {}", cmd);
			Process exec = Runtime.getRuntime().exec(new String[] {"sh","-c",cmd}, null, new File(ExecUtil.cert));
			data = IOUtils.toString(exec.getInputStream(), StandardCharsets.UTF_8);
			log.info("exec result: {}", data);
			exec.waitFor(3, TimeUnit.SECONDS);
			exec.destroy();
		}catch(Exception e) {
			log.info("fail to exec cmd: {}", e.getMessage());
		}
		return data;
	}

	public static String getParam(HttpServerExchange exchange, String name) {
		Deque<String> deque = exchange.getQueryParameters().get(name);
		if(deque!=null && deque.size()==1) {
			return deque.getFirst();
		}else {
			return StringUtils.EMPTY;
		}
	}

	@SuppressWarnings({ "rawtypes" })
	public static class Pager implements Serializable {
		private int totalRows = -1;
		private int pageSize = 12;
		private int totalPages = 1;
		private int currentPage = 1;
		private int pageWindow = 7;//页码窗口
		private List elements = null;
		private List others = null;
		private String direction = null;
		private String properties = null;

		/**
		 * 未初始化分页，请调用init初始化
		 */
		public Pager() {}
		
		/**
		 * 初始化分页，调用page(n)设置页码
		 */
		public Pager(int totalRows, int pageSize) {
			this.pageSize = pageSize;
			init(totalRows);
		}

		/**
		 * 初始化
		 */
		public void init (int totalRows) {
			this.totalRows = totalRows > 0 ? totalRows : 0;
			this.totalPages = pageSize > 0 ? this.totalRows / pageSize + (this.totalRows % pageSize > 0 ? 1 : 0) : (this.totalRows > 0 ? 1 : 0);
		}
		
		/**
		 * 如果没有初始化，则请初始化
		 */
		public boolean notInitialized() {
			return totalRows == -1;
		}
		
		/**
		 * 跳转页，从1开始
		 */
		public void page(int page) {
			if(page>0 && (notInitialized() || page<=totalPages)) {
				currentPage = page;
			}
		}
		
		/**
		 * 设置需要显示的页码窗口大小，默认10个页码
		 */
		public void pageWindow(int pageWindow) {
			this.pageWindow = pageWindow;
		}

		public int getPageSize() {
			return pageSize;
		}

		public void setPageSize(int pageSize) {
			this.pageSize = pageSize;
		}
		
		public void setPageSize(String pageSize, int defaultPageSize) {
			try {
				this.pageSize = Integer.parseInt(pageSize);
			}catch(Exception e) {
				this.pageSize = defaultPageSize;
			}
		}

		public int getStartRow() {
			return (currentPage - 1) * pageSize;
		}
		
		public int getEndRow() {
			return currentPage * pageSize - 1;
		}
		
		public int getStartPage() {
			int pageMiddle = pageWindow / 2;
			int startPage = currentPage <= pageMiddle ? 1 : currentPage - pageMiddle;
			int endPage = startPage + pageWindow -1;
			endPage = endPage > totalPages ? totalPages : endPage;
			if(endPage==totalPages && startPage>1 && endPage-startPage<pageWindow-1) {
				int leftShift1 = pageWindow-(endPage-startPage)-1;
				int leftShift2 = startPage - 1;
				startPage -= leftShift1 < leftShift2 ? leftShift1 : leftShift2;
			}
			return startPage;
		}
		
		public int getEndPage() {
			int endPage = getStartPage() + pageWindow -1 ;
			endPage = endPage > totalPages ? totalPages : endPage;
			return endPage < 1 ? 1 : endPage;
		}
		
		public int getTotalPages() {
			return totalPages;
		}

		public int getCurrentPage() {
			return currentPage;
		}

		public int getTotalRows() {
			return totalRows;
		}

		public String getDirection() {
			return direction;
		}

		/**
		 * asc或desc
		 */
		public void setDirection(String direction) {
			if("asc".equalsIgnoreCase(direction) || "desc".equalsIgnoreCase(direction)) {
				this.direction = direction.toUpperCase();
			}
		}

		public String getProperties() {
			return properties;
		}

		/**
		 * id或name,age
		 */
		public void setProperties(String properties) {
			this.properties = properties;
		}

		/**
		 * 获取元素列表
		 */
		public List getElements() {
			return elements;
		}

		public void setElements(List elements) {
			this.elements = elements;
		}

		/**
		 * 获取附加信息，最好将附加信息绑定到element实体上
		 */
		public List getOthers() {
			return others;
		}

		public void setOthers(List others) {
			this.others = others;
		}
	}
}
