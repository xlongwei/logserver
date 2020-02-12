package com.xlongwei.logserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.LineNumberReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.OS;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.networknt.utility.StringUtils;
import com.networknt.utility.Util;

/**
 * 使用find+grep检索日志
 * @author xlongwei
 *
 */
public class ExecUtil {
	public static boolean isWindows = OS.isFamilyWindows();
	public static String logs = firstNotBlank(System.getProperty("logfile"), "logs/all.logs"), dir = new File(logs).getParent();
	public static String cert = firstNotBlank(System.getProperty("certdir"), "/soft/cert");
	private static Logger log = LoggerFactory.getLogger(ExecUtil.class);
	
	/** 按日期排倒序，all.logs按当天排首位 */
	public static Comparator<String> logsComparator = new Comparator<String>() {
		@Override
		public int compare(String o1, String o2) {
			String s1 = o1.substring(o1.lastIndexOf('.')+1);
			String s2 = o2.substring(o2.lastIndexOf('.')+1);
			SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
			try {
				Date d1 = StringUtils.isBlank(s1)||s1.indexOf('-')==-1 ? null : df.parse(s1);
				Date d2 = StringUtils.isBlank(s2)||s2.indexOf('-')==-1 ? null : df.parse(s2);
				return d1==null ? -1 : (d2==null ? 1 : d2.compareTo(d1));
			}catch(Exception e) {
				return 0;
			}
		}
	};
	
	/** 搜索dir目录下的日志文件，列出包含search文本的文件名 */
	public static List<String> list(String search) {
		List<String> files = new ArrayList<>();
		if(StringUtils.isNotBlank(search)) {
			CommandLine command = null;
			if(isWindows) {
				//find /C "a" *
				command = CommandLine.parse("find");
				command.addArgument("/C");
				command.addArgument("\"${search}\"", false);
				command.addArgument("*");
			}else {
				//find ! -name *.gz -exec grep -li '${search}' {} \;  find . -type f -exec zgrep -li '${search}' {} \;
				//1，命令行的分号需要转义\;或引号';'而CommandLine不需要；2，为提高效率可以自定义查找最近几天内的日志，这里查找未gz压缩的日志（通过gz规则控制数量）
				command = CommandLine.parse("find ! -name *.gz -exec grep -li ''${search}'' {} ;"); //find . -type f -exec zgrep -li ''${search}'' {} ;
			}
			Map<String, Object> substitutionMap = new HashMap<>(4);
			substitutionMap.put("search", search);
			command.setSubstitutionMap(substitutionMap);
			String find = exec(dir, command);
			String[] lines = find.split("[\r\n]+");
			for(String line : lines) {
				if(StringUtils.isBlank(line)) {
					continue;
				}else if(isWindows) {
					int e = line.indexOf(':'), s = e==-1 ? -1 : line.lastIndexOf(' ', e);
					if(s == -1) {
						continue;
					}else if(Integer.parseInt(line.substring(e+1).trim()) > 0) {
						files.add(line.substring(s, e).trim().toLowerCase());
					}
				}else {
					files.add(FilenameUtils.getName(line));
				}
			}
		}else {
			File dir = new File(ExecUtil.dir);
			File[] logs = dir.listFiles(new FileFilter() {
				@Override
				public boolean accept(File pathname) {
					return pathname.getName().endsWith(".gz") == false;
				}
			});
			for(File file : logs) {
				files.add(file.getName());
			}
		}
		files.sort(logsComparator);
		return files;
	}
	
	/** 计算logs总行数 */
	public static int count(String logs) {
		if(isWindows) {
			File file = new File(dir, logs);
			try(LineNumberReader lineNumberReader = new LineNumberReader(new FileReader(file))){
				lineNumberReader.skip(file.length());
				return lineNumberReader.getLineNumber();
			}catch(Exception e) {
				log.warn(e.getMessage());
				return 0;
			}
		}else {
			CommandLine command = CommandLine.parse("wc -l " + logs);
			String count = exec(dir, command);
			return Integer.parseInt(count.substring(0, count.indexOf(' ')));
		}
	}
	
	/** 获取logs第几页的内容 */
	public static String page(String logs, int from, int to) {
		if(isWindows) {
			File file = new File(dir, logs);
			try(LineNumberReader lineNumberReader = new LineNumberReader(new FileReader(file))){
				StringBuilder page = new StringBuilder();
				int lineNumber = lineNumberReader.getLineNumber();
				while(lineNumber <= to) {
					String line = lineNumberReader.readLine();
					if(line == null) break;
					if(lineNumber >= from) {
						page.append(line);
						page.append("\n");
					}
					lineNumber = lineNumberReader.getLineNumber();
				}
				return page.toString();
			}catch(Exception e) {
				log.warn(e.getMessage());
				return "";
			}
		}else {
			CommandLine command = CommandLine.parse("sed -n ''"+(from+1)+","+(to+1)+"p'' "+logs);
			return exec(dir, command);
		}
	}
	
	/** 搜索search在logs出现的所有行号 */
	public static List<Integer> lines(String logs, String search) {
		List<Integer> lines = new ArrayList<>();
		if(StringUtils.isNotBlank(search)) {
			if(isWindows) {
				File file = new File(dir, logs);
				try(LineNumberReader lineNumberReader = new LineNumberReader(new FileReader(file))){
					String line = null;
					do{
						line = lineNumberReader.readLine();
						if(StringUtils.isNotBlank(line) && line.contains(search)) {
							lines.add(lineNumberReader.getLineNumber());
						}
					}while(line != null);
				}catch(Exception e) {
					log.warn(e.getMessage());
				}
			}else {
				CommandLine command = CommandLine.parse("grep -n "+search+" "+logs);
				String line = exec(dir, command); //分隔行：\\r?\\n|\\r，忽略空行：[\r?\n|\r]+，Java8：\\R
				for(String row : line.split("\\R")) {
					if(StringUtils.isBlank(row)) continue;
					int n = Util.parseInteger(row.substring(0, row.indexOf(':')));
					if(n>0) lines.add(n);
				}
			}
		}
		return lines;
	}
	
	/** 计算所有行号分布在哪些页码 */
	public static List<Integer[]> pages(List<Integer> lines, int pageSize) {
		List<Integer[]> pages = new ArrayList<>();
		int currentPage = 0, idx = 0;
		for(Integer line : lines) {
			int calcPage = (line-1)/pageSize+1;
			if(calcPage>currentPage) {
				pages.add(new Integer[] {calcPage, 1});
				currentPage = calcPage;
				idx++;
			}else {
				Integer[] arr = pages.get(idx-1);
				arr[1] = arr[1]+1;
			}
		}
		return pages;
	}
	
	/** 获取最后的num行内容 */
	public static String tail(String logs, int num) {
		if(isWindows) {
			File file = new File(dir, logs);
			String[] tail = new String[num];
			int idx = 0;
			try(LineNumberReader lineNumberReader = new LineNumberReader(new FileReader(file))){
				String line = null;
				while((line=lineNumberReader.readLine()) != null) {
					tail[idx] = line;
					idx = (idx+1)%num;
				}
				final StringBuilder sb = new StringBuilder();
		        for (int i = 0; i < num; i++) {
		        	sb.append("\r\n");
		            sb.append(tail[idx]);
		            idx = (idx+1)%num;
		        }
		        return sb.length()>0 ? sb.substring(2) : sb.toString();
			}catch(Exception e) {
				log.warn(e.getMessage());
				return "";
			}
		}else {
			CommandLine command = CommandLine.parse("tail -n "+num+" "+logs);
			return exec(dir, command);
		}
	}
	
	/** 在dir目录执行command命令，返回string文本 */
	public static String exec(String dir, CommandLine command) {
		Executor exe = new DefaultExecutor();
		exe.setWorkingDirectory(new File(dir));
		exe.setExitValues(new int[]{0,1,2});
		
		ExecuteWatchdog watchDog = new ExecuteWatchdog(60000);
		exe.setWatchdog(watchDog);
		
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ExecuteStreamHandler streamHandler = new PumpStreamHandler(baos);
		exe.setStreamHandler(streamHandler);
		
		try{
			exe.execute(command);
			log.info("dir: {}, exec: {}, length: {}", dir, command, baos.size());
			String exec = baos.toString(isWindows ? "GBK" : "UTF-8");
			//log.info(exec);
			return exec;
		}catch(Exception e) {
			log.warn("dir, {}, exec: {}, ex: {}", dir, command, e.getMessage());
			return "";
		}
	}
	
	public static String firstNotBlank(String ... strs) {
		if(strs!=null && strs.length>0) {
			for(String str : strs) {
				if(StringUtils.isNotBlank(str)) {
					return str;
				}
			}
		}
		return null;
	}
	
}
