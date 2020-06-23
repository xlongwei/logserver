package com.xlongwei.logserver;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.alidns.model.v20150109.DescribeDomainRecordsRequest;
import com.aliyuncs.alidns.model.v20150109.DescribeDomainRecordsResponse;
import com.aliyuncs.alidns.model.v20150109.DescribeDomainRecordsResponse.Record;
import com.aliyuncs.alidns.model.v20150109.UpdateDomainRecordRequest;
import com.aliyuncs.cms.model.v20190101.PutCustomMetricRequest;
import com.aliyuncs.cms.model.v20190101.PutCustomMetricResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.config.Config;
import com.networknt.handler.LightHttpHandler;
import com.networknt.utility.StringUtils;
import com.networknt.utility.Tuple;
import com.networknt.utility.Util;

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
	private ObjectMapper mapper = Config.getInstance().getMapper();
	private String json = MimeMappings.DEFAULT.getMimeType("json");
	private Logger log = LoggerFactory.getLogger(getClass());
	private IAcsClient client = null;
	private IClientProfile profile = null;
	private boolean metricEnabled = false;
	private String domainName = ExecUtil.firstNotBlank(System.getenv("domainName"), "xlongwei.com");
	private String recordId = ExecUtil.firstNotBlank(System.getenv("recordId"), "4012091293697024");
	private LinkedList<String[]> metrics = new LinkedList<>();
	private Map<String, Tuple<AtomicInteger, AtomicInteger>> metricsMap = new HashMap<>();
	private ScheduledExecutorService scheduledService = Executors.newSingleThreadScheduledExecutor();
	private String wellKnown = ExecUtil.firstNotBlank(System.getProperty("wellKnown"), "/soft/statics")+"/.well-known/acme-challenge";
	
	public PageHandler() {
		String accessKeyId = System.getenv("accessKeyId"), regionId = ExecUtil.firstNotBlank(System.getenv("regionId"), "cn-hangzhou"), secret = System.getenv("secret");
		metricEnabled = StringUtils.isNotBlank(accessKeyId) && StringUtils.isNotBlank(secret) && !"false".equalsIgnoreCase(System.getenv("metricEnabled"));
		log.info("accessKeyId={}, metricEnabled={}, regionId={}, recordId={}", accessKeyId, metricEnabled, regionId, recordId);
		client = new DefaultAcsClient(profile = DefaultProfile.getProfile(regionId, accessKeyId, secret));
		scheduledService.scheduleWithFixedDelay(() -> {
				putCustomMetrics();
		}, 15, 15, TimeUnit.SECONDS);
		//每4个小时清理一下统计数据
		Calendar calendar = Calendar.getInstance();
		long minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY)*60+calendar.get(Calendar.MINUTE), range = 4*60, minuteToWait = range - (minuteOfDay%range);
		log.info("metrics map wait {} minutes to clear", minuteToWait);
		scheduledService.scheduleWithFixedDelay(() -> {
				log.info("metrics map clear");
				metricsMap.clear();
		}, minuteToWait, range, TimeUnit.MINUTES);
	}

	private void putCustomMetrics() {
		List<PutCustomMetricRequest.MetricList> metricListList = new ArrayList<PutCustomMetricRequest.MetricList>();
		String[] metric = null;
		String time = String.valueOf(System.currentTimeMillis());
		while((metric=metrics.pollFirst())!=null) {
			PutCustomMetricRequest.MetricList metricList1 = new PutCustomMetricRequest.MetricList();
		    metricList1.setGroupId("0");
		    metricList1.setMetricName(metric[0]);
		    metricList1.setValues("{\"value\":"+metric[1]+"}");
		    metricList1.setDimensions("{\"appName\":\""+metric[2]+"\"}");
		    metricList1.setTime(time);
		    metricList1.setType("0");
		    metricListList.add(metricList1);
		    //add metric to metricsMap
		    try {
		    	int dot = metric[1].indexOf('.');
		    	Integer value = Integer.valueOf(dot==-1 ? metric[1] : metric[1].substring(0, dot));
		    	if(value.intValue() > 0) {
			        String key = metric[0]+"."+metric[2];
			        Tuple<AtomicInteger, AtomicInteger> tuple = metricsMap.get(key);
			        if(tuple == null) {
			        	metricsMap.put(key, new Tuple<>(new AtomicInteger(1), new AtomicInteger(value)));
			        }else {
			        	tuple.first.incrementAndGet();
			        	tuple.second.getAndAdd(value);
			        }
		    	}
		    }catch(Exception e) {
		    	//ignore
		    }
		}
		if(metricListList.isEmpty() || metricEnabled==false) {
			return;
		}
		try {
			log.info("metrics={}", metricListList.size());
			PutCustomMetricRequest request = new PutCustomMetricRequest();
			request.setMetricLists(metricListList);
			PutCustomMetricResponse response = client.getAcsResponse(request);
			log.info("code={}, message={}, requestId={}", response.getCode(), response.getMessage(), response.getRequestId());
		}catch(Exception e) {
			log.warn("metrics upload failed: {}", e.getMessage());
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
		}else {
			response = logger(exchange);
		}
		exchange.setStatusCode(200);
		exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, json);
		exchange.getResponseSender().send(StringUtils.trimToEmpty(response));
	}

	private String logger(HttpServerExchange exchange) throws JsonProcessingException {
		Map<String, Deque<String>> queryParameters = exchange.getQueryParameters();
		Deque<String> loggerParams = queryParameters.get("logger");
		List<ch.qos.logback.classic.Logger> loggers = null;
		if(loggerParams!=null && loggerParams.size()>0) {
			Set<String> loggerNames = loggerParams.stream().map(loggerName -> StringUtils.trimToEmpty(loggerName)).collect(Collectors.toSet());
			if(!loggerNames.isEmpty()) {
				loggers = loggerNames.stream().map(loggerName -> (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(loggerName)).filter(logger -> logger!=null).collect(Collectors.toList());
				if(!loggers.isEmpty()) {
					Deque<String> levelParams = queryParameters.get("level");
					if(levelParams!=null && levelParams.size()>0) {
						String levelName = levelParams.getFirst();
						Level level = Level.toLevel(levelName, null);
						loggers.forEach(logger -> {
							log.info("change logger:{} level from:{} to:{}", logger.getName(), logger.getLevel(), level);
							logger.setLevel(level);
						});
					}
				}
			}
		}
		if(loggers == null) {
			LoggerContext lc = (LoggerContext)LoggerFactory.getILoggerFactory();
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

	private String list(HttpServerExchange exchange) throws JsonProcessingException {
		String search = getParam(exchange, "search");
		List<String> list = ExecUtil.list(search);
		log.info("page logs list: {}", search);
		return mapper.writeValueAsString(list);
	}

	private String page(HttpServerExchange exchange) throws JsonProcessingException {
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
			List<Integer> lines = ExecUtil.lines(logs, search);
			List<Integer[]> pages = ExecUtil.pages(lines, pager.getPageSize());
			pager.setOthers(pages);
		}
		log.info("page logs page: {}, {}", logs, pager.getCurrentPage());
		return mapper.writeValueAsString(pager);
	}

	private String metric(HttpServerExchange exchange) throws JsonProcessingException {
		String metricName = getParam(exchange, "metricName");
		String value = getParam(exchange, "value");
		String appName = getParam(exchange, "appName");
		if(StringUtils.isNotBlank(metricName) && StringUtils.isNotBlank(appName) && StringUtils.isNotBlank(value)) {
			String[] metric = new String[] {metricName, value, appName};
			metrics.offerLast(metric);
			return "{\"metric\":"+metricEnabled+"}";
		}else {
			Map<String, Integer> map = new TreeMap<>();
			for(String key : metricsMap.keySet()) {
				Tuple<AtomicInteger, AtomicInteger> tuple = metricsMap.get(key);
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
				if(new File(ExecUtil.cert, "account.key").exists()==false) {
					ExecUtil.exec(ExecUtil.cert, CommandLine.parse("openssl genrsa 4096 > account.key"));
				}
				cmd = "openssl rsa -in account.key -pubout";
				data = ExecUtil.exec(ExecUtil.cert, CommandLine.parse(cmd));
				data = data.substring(data.indexOf('-'));
				break;
			case "validateCSR":
				if(new File(ExecUtil.cert, "domain.key").exists()==false) {
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
			if(StringUtils.isNotBlank(data)) {
				data = data.replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\r");
				return "{\"data\":\""+data+"\"}";
			}
		}
		return null;
	}
	
	private String alidns(String value) {
		try {
			if("true".equals(value) || value.startsWith("false")) {
				DescribeDomainRecordsRequest request = new DescribeDomainRecordsRequest();
		        request.setRegionId(profile.getRegionId());
		        request.setDomainName(domainName);
		        DescribeDomainRecordsResponse response = client.getAcsResponse(request);
		        List<Record> records = response.getDomainRecords();
		        Optional<String> record = records.stream().filter(r -> recordId.equals(r.getRecordId())).map(r -> r.getValue()).findFirst();
		        return record.orElse("false");
			}else {
		        UpdateDomainRecordRequest request = new UpdateDomainRecordRequest();
		        request.setRegionId(profile.getRegionId());
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

	@SuppressWarnings({ "rawtypes", "serial" })
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
			if(notInitialized() && page>0) currentPage = page;
			else if(page > 0 && page <= totalPages) currentPage = page;
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
			if("asc".equalsIgnoreCase(direction) || "desc".equalsIgnoreCase(direction)) this.direction = direction.toUpperCase();
			else System.out.println("bad direction type: "+direction);
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
