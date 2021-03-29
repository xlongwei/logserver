package com.xlongwei.logserver;

import java.nio.charset.Charset;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.pattern.PatternLayoutEncoderBase;

/**
 * lajax和filebeat日志器仅输出消息体
 * @author xlongwei
 *
 */
public class PatternsLayoutEncoder extends PatternLayoutEncoderBase<ILoggingEvent> {
	Layout<ILoggingEvent> simple = null, origin = null;
	
    @Override
    public void start() {
    	PatternLayout patternLayout = new PatternLayout();
        patternLayout.setContext(context);
        patternLayout.setPattern(getPattern());
        patternLayout.setOutputPatternAsHeader(outputPatternAsHeader);
        patternLayout.start();
        origin = patternLayout;
    	patternLayout = new PatternLayout();
        patternLayout.setContext(context);
        patternLayout.setPattern("%msg%n");
        patternLayout.setOutputPatternAsHeader(outputPatternAsHeader);
        patternLayout.start();
    	simple = patternLayout;
    	super.start();
    }
    
    @Override
    public byte[] encode(ILoggingEvent event) {
    	String name = event.getLoggerName();
    	if("lajax".equals(name) || "filebeat".equals(name)) {
    		layout = simple;
    	}else {
    		layout = origin;
    	}
    	String txt = layout.doLayout(event);
    	txt = MaskUtil.mask(txt);
    	TailCallback.notify(txt);
    	Charset charset = getCharset();
    	if (charset == null) {
            return txt.getBytes();
        } else {
            return txt.getBytes(charset);
        }
    }
}
