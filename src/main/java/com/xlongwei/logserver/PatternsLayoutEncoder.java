package com.xlongwei.logserver;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Layout;

/**
 * lajax和filebeat日志器仅输出消息体
 * @author xlongwei
 *
 */
public class PatternsLayoutEncoder extends PatternLayoutEncoder {
	Layout<ILoggingEvent> simple = null, origin = null;
	
    @Override
    public void start() {
    	PatternLayout patternLayout = new PatternLayout();
        patternLayout.setContext(context);
        patternLayout.setPattern("%msg%n");
        patternLayout.setOutputPatternAsHeader(outputPatternAsHeader);
        patternLayout.start();
    	super.start();
    	origin = getLayout();
    	simple = patternLayout;
    }
    
    @Override
    public byte[] encode(ILoggingEvent event) {
    	String name = event.getLoggerName();
    	if("lajax".equals(name) || "filebeat".equals(name)) {
    		layout = simple;
    	}else {
    		layout = origin;
    	}
    	return super.encode(event);
    }
}
