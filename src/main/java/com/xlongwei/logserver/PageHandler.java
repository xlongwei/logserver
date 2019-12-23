package com.xlongwei.logserver;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.cms.model.v20190101.PutCustomMetricRequest;
import com.aliyuncs.cms.model.v20190101.PutCustomMetricResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.config.Config;
import com.networknt.handler.LightHttpHandler;
import com.networknt.utility.StringUtils;
import com.networknt.utility.Util;

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
	private boolean metricEnabled = false;
	private LinkedList<String[]> metrics = new LinkedList<>();
	private ScheduledExecutorService scheduledService = Executors.newSingleThreadScheduledExecutor();
	
	public PageHandler() {
		String accessKeyId = System.getenv("accessKeyId"), secret = System.getenv("secret");
		metricEnabled = StringUtils.isNotBlank(accessKeyId) && StringUtils.isNotBlank(secret);
		log.info("accessKeyId={}, metricEnabled={}", accessKeyId, metricEnabled);
		if(metricEnabled) {
			DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou", accessKeyId, secret);
			client = new DefaultAcsClient(profile);
			scheduledService.scheduleWithFixedDelay(new Runnable() {
				@Override
				public void run() {
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
					}
					log.info("metrics={}", metricListList.size());
					if(metricListList.isEmpty()) {
						return;
					}
					try {
						PutCustomMetricRequest request = new PutCustomMetricRequest();
						request.setMetricLists(metricListList);
						PutCustomMetricResponse response = client.getAcsResponse(request);
						log.info("code={}, message={}, requestId={}", response.getCode(), response.getMessage(), response.getRequestId());
					}catch(Exception e) {
						log.warn("metrics upload failed: {}", e.getMessage());
					}
				}
			}, 15, 15, TimeUnit.SECONDS);
		}
	}

	@Override
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		String type = getParam(exchange, "type");
		String search = getParam(exchange, "search");
		String response = null;
		switch (type) {
		case "list":
			List<String> list = ExecUtil.list(search);
			response = mapper.writeValueAsString(list);
			log.info("page logs list: {}", search);
			break;
		case "page":
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
			response = mapper.writeValueAsString(pager);
			log.info("page logs page: {}, {}", logs, pager.getCurrentPage());
			break;
		case "metric":
			if(metricEnabled) {
				String metricName = getParam(exchange, "metricName");
				String value = getParam(exchange, "value");
				String appName = getParam(exchange, "appName");
				if(StringUtils.isNotBlank(metricName) && StringUtils.isNotBlank(appName) && StringUtils.isNotBlank(value)) {
					String[] metric = new String[] {metricName, value, appName};
					metrics.offerLast(metric);
					response = "{\"metric\":true}";
				}
			}
			break;
		default:
			break;
		}
		if (response != null) {
			exchange.setStatusCode(200);
			exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, json);
			exchange.getResponseSender().send(response);
		}
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
