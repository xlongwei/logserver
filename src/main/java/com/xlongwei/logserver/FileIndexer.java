package com.xlongwei.logserver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.networknt.config.Config;
import com.networknt.server.ShutdownHookProvider;
import com.networknt.server.StartupHookProvider;
import com.networknt.utility.Tuple;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileIndexer implements StartupHookProvider, ShutdownHookProvider {
    private static Logger log = LoggerFactory.getLogger(FileIndexer.class);
    private static String lightSearch = System.getProperty("light-search", "http://localhost:9200");
    private static boolean logfile = System.getProperty("logfile") != null;
    private static boolean useIndexer = Boolean.getBoolean("useIndexer");
    private static File logs = new File(ExecUtil.logs);
    private static CloseableHttpClient client = HttpClientBuilder.create().build();
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
        try {
            FileIndexer.client.close();
            log.info("client close");
        } catch (Exception e) {
        }
    }

    public static CloseableHttpResponse execute(HttpUriRequest request) throws Exception {
        return client.execute(request);
    }

    static class IndexerListener extends TailerListenerAdapter {
        private int number = 0;
        private List<Tuple<Integer, String>> lines = new LinkedList<>(), docs = null;

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
            number += 3;// 有3行索引不到
            PageHandler.scheduler.submit(this::open);
        }

        @Override
        public void endOfFileReached() {
            docs = lines;
            lines = new LinkedList<>();
            PageHandler.scheduler.submit(this::docs);
        }

        private void open() {
            try {
                HttpPost post = new HttpPost(lightSearch + "/service/index/open?name=logserver");
                // logserver=day:string,number:int,line:text
                List<Map<String, String>> fields = new LinkedList<>();
                Map<String, String> map = new HashMap<>();
                map.put("field", "day");
                map.put("type", "string");
                fields.add(map);
                map = new HashMap<>();
                map.put("field", "number");
                map.put("type", "store");
                fields.add(map);
                map = new HashMap<>();
                map.put("field", "line");
                map.put("type", "text");
                fields.add(map);
                Object body = Collections.singletonMap("fields", fields);
                String string = Config.getInstance().getMapper().writeValueAsString(body);
                post.setEntity(new StringEntity(string, StandardCharsets.UTF_8));
                client.execute(post);
            } catch (Exception e) {
            }
        }

        private void docs() {
            if (this.docs == null || this.docs.isEmpty()) {
                return;
            }
            try {
                HttpPost post = new HttpPost(lightSearch + "/service/index/docs?name=logserver");
                List<Map<String, String>> docs = new LinkedList<>();
                final String day = LocalDate.now().toString();
                this.docs.forEach(pair -> {
                    Map<String, String> doc = new HashMap<>();
                    doc.put("day", day);
                    doc.put("number", pair.first.toString());
                    doc.put("line", pair.second);
                    docs.add(doc);
                });
                Object body = Collections.singletonMap("add", docs);
                String string = Config.getInstance().getMapper().writeValueAsString(body);
                post.setEntity(new StringEntity(string, StandardCharsets.UTF_8));
                client.execute(post);
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
                    HttpPost post = new HttpPost(lightSearch + "/service/logserver/delete?day=" + day);
                    client.execute(post);
                } catch (Exception e) {
                }
            }
        }
    }
}
