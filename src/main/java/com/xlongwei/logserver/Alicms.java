package com.xlongwei.logserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.aliyuncs.cms.model.v20190101.PutCustomMetricRequest;
import com.aliyuncs.cms.model.v20190101.PutCustomMetricResponse;
import com.networknt.utility.Tuple;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 未使用cms功能时，PageHandler不会加载相关类
 * @author xlongwei
 */
public class Alicms {
    private static final Logger log = LoggerFactory.getLogger(Alicms.class);

    public static void putCustomMetrics() {
		List<PutCustomMetricRequest.MetricList> metricListList = new ArrayList<>();
		String[] metric = null;
		String time = String.valueOf(System.currentTimeMillis());
		while((metric=PageHandler.metrics.pollFirst())!=null) {
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
			        Tuple<AtomicInteger, AtomicInteger> tuple = PageHandler.metricsMap.get(key);
			        if(tuple == null) {
			        	PageHandler.metricsMap.put(key, new Tuple<>(new AtomicInteger(1), new AtomicInteger(value)));
			        }else {
			        	tuple.first.incrementAndGet();
			        	tuple.second.getAndAdd(value);
			        }
		    	}
		    }catch(Exception e) {
		    	//ignore
		    }
		}
		if(metricListList.isEmpty()) {
			return;
		}
		try {
			log.info("metrics={}", metricListList.size());
			PutCustomMetricRequest request = new PutCustomMetricRequest();
			request.setMetricLists(metricListList);
			PutCustomMetricResponse response = PageHandler.client.getAcsResponse(request);
			log.info("code={}, message={}, requestId={}", response.getCode(), response.getMessage(), response.getRequestId());
		}catch(Exception e) {
			log.warn("metrics upload failed: {}", e.getMessage());
		}
	}
}
