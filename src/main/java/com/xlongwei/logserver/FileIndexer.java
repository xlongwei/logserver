package com.xlongwei.logserver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.aliyuncs.http.FormatType;
import com.aliyuncs.http.HttpRequest;
import com.aliyuncs.http.MethodType;
import com.networknt.config.Config;
import com.networknt.server.ShutdownHookProvider;
import com.networknt.server.StartupHookProvider;
import com.networknt.utility.Tuple;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileIndexer implements StartupHookProvider, ShutdownHookProvider {
    private static Logger log = LoggerFactory.getLogger(FileIndexer.class);
    private static String lightSearch = System.getProperty("light-search", "http://localhost:9200");
    private static boolean logfile = System.getProperty("logfile") != null;
    private static boolean useIndexer = Boolean.getBoolean("useIndexer");
    private static File logs = new File(ExecUtil.logs);
    Tailer tailer = null;

    @Override
    public void onStartup() {
        log.info("logfile={} useIndexer={}", logfile, useIndexer);
        if (logfile && useIndexer) {
            tailer = Tailer.create(logs, StandardCharsets.UTF_8, new IndexerListener(), 1000, true, false,
                    IOUtils.DEFAULT_BUFFER_SIZE);
            log.info("tailer start");
        }
    }

    @Override
    public void onShutdown() {
        if (tailer != null) {
            log.info("tailer stop");
            tailer.stop();
        }
    }

    static class IndexerListener extends TailerListenerAdapter {
        private int number = 0;
        private Deque<Tuple<Integer, String>> lines = new LinkedList<>();

        @Override
        public void fileRotated() {
            number = 0;
            PageHandler.scheduler.submit(this::delete);
        }

        @Override
        public void handle(String line) {
            number++;
            lines.add(new Tuple<>(number, line));
        }

        @Override
        public void init(Tailer tailer) {
            number = ExecUtil.count(logs.getName());
            log.info("number={}", number);
            // number += 3;// 有3行索引不到
            PageHandler.scheduler.schedule(this::open, 3, TimeUnit.SECONDS);
            PageHandler.scheduler.scheduleWithFixedDelay(this::docs, 10, 10, TimeUnit.SECONDS);
        }

        @Override
        public void endOfFileReached() {
        }

        private void open() {
            try {
                // logserver=day:string:no,number:int,line:text
                List<Map<String, String>> fields = new LinkedList<>();
                Map<String, String> map = new HashMap<>();
                map.put("field", "day");
                map.put("type", "string");
                map.put("store", "no");
                fields.add(map);
                map = new HashMap<>();
                map.put("field", "number");
                map.put("type", "int");
                fields.add(map);
                map = new HashMap<>();
                map.put("field", "line");
                map.put("type", "text");
                fields.add(map);
                Map<String, Object> body = new HashMap<>();
                body.put("fields", fields);
                body.put("realtime", "true");
                String bodyString = Config.getInstance().getMapper().writeValueAsString(body);
                HttpRequest request = new HttpRequest(
                        lightSearch + "/service/index/open?name=logserver&token=" + LajaxHandler.token);
                request.setSysMethod(MethodType.POST);
                request.setHttpContent(bodyString.getBytes(CharEncoding.UTF_8), CharEncoding.UTF_8, FormatType.JSON);
                PageHandler.httpClient.syncInvoke(request);
            } catch (Exception e) {
            }
        }

        private void docs() {
            if (lines.isEmpty()) {
                return;
            }
            final String day = LocalDate.now().toString();
            try {
                List<Map<String, String>> docs = new LinkedList<>();
                Tuple<Integer, String> pair = lines.poll();
                while (pair != null) {
                    Map<String, String> doc = new HashMap<>();
                    doc.put("day", day);
                    doc.put("number", pair.first.toString());
                    doc.put("line", pair.second);
                    docs.add(doc);
                    if (docs.size() >= 100) {
                        log.info("lines remaining={}", lines.size());
                        break;
                    } else {
                        pair = lines.poll();
                    }
                }
                log.info("docs={}", docs.size());
                Object body = Collections.singletonMap("add", docs);
                String bodyString = Config.getInstance().getMapper().writeValueAsString(body);
                HttpRequest request = new HttpRequest(lightSearch + "/service/index/docs?name=logserver");
                request.setSysMethod(MethodType.POST);
                request.setHttpContent(bodyString.getBytes(CharEncoding.UTF_8), CharEncoding.UTF_8, FormatType.JSON);
                PageHandler.httpClient.syncInvoke(request);
            } catch (Exception e) {
            }
        }

        private void delete() {
            List<String> list = ExecUtil.list(null);
            if (list.size() > 1) {
                String last = list.get(list.size() - 1);
                String day = last.substring(last.lastIndexOf(".") + 1);
                try {
                    // 删除day以前的日志索引
                    HttpRequest request = new HttpRequest(lightSearch + "/service/logserver/delete?day=" + day);
                    request.setSysMethod(MethodType.POST);
                    PageHandler.httpClient.syncInvoke(request);
                } catch (Exception e) {
                }
            }
        }
    }
}
