package com.xlongwei.logserver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileBeat {
	private static Logger log = LoggerFactory.getLogger("filebeat");
	private static ExecutorService es = Executors.newCachedThreadPool();
	private static SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

	public static void main(String[] args) {
		String filebeat = System.getProperty("filebeat");
		log.info("filebeat {} INFO files: {}", df.format(new Date()), filebeat);
		if(filebeat!=null && filebeat.length()>0) {
			for(String filepath : filebeat.split("[,;]")) {
				File file = new File(filepath);
				Tailer tailer = new Tailer(file, StandardCharsets.UTF_8, new TailerListenerAdapter() {
					@Override
					public void handle(String line) {
						if(line.contains(" WARN ")) {
							log.warn(line);
						}else if(line.contains(" ERROR ")) {
							log.error(line);
						}else {
							log.info(line);
						}
					}
				}, 1000, true, false, 4096);
				es.submit(tailer);
			}
		}
	}

}
