package com.xlongwei.logserver;

import java.util.regex.Pattern;

import com.networknt.utility.StringUtils;

/**
 * 日志数据脱敏
 * @author xlongwei
 *
 */
public class MaskUtil {
	private static String[] maskArray = StringUtils.trimToEmpty(System.getProperty("mask")).split("[;]");
	private static boolean maskEnabled = maskArray!=null && maskArray.length>0;
	private static Pattern[] patternArray = maskEnabled ? new Pattern[maskArray.length] : null;
	
	static {
		if(maskEnabled) {
			try {
				for(int i=0,len=maskArray.length;i<len;i++) {
					String mask = maskArray[i];
					int left = mask.indexOf('('), comma = left==-1 ? -1 : mask.indexOf(',', left+1), right = comma==-1 ? -1 : mask.indexOf(')', comma+1);
					int from = Integer.parseInt(mask.substring(left+1, comma)), to = Integer.parseInt(mask.substring(comma+1, right));
					//password(3,7) => password(.{3})(.{1,4}) => pasword$1****
					String patternStr = mask.substring(0, left) + "(.{"+from+"})(.{1,"+(to-from)+"})";
					String replaceStr = mask.substring(0, left) + "$1" + StringUtils.rightPad("*", to-from, '*');
					System.out.println(mask + " => " + patternStr + " => " + replaceStr);
					patternArray[i] = Pattern.compile(patternStr);
					maskArray[i] = replaceStr;
				}
			}catch(Exception e) {
				maskEnabled = false;
				System.out.println("bad mask config: " + System.getProperty("mask"));
			}
		}
	}

	/** 根据配置进行数据脱敏 */
	public static String mask(String str) {
		if(maskEnabled && StringUtils.isNotBlank(str)) {
			String maskStr = str;
			try {
				for(int i=0,len=maskArray.length;i<len;i++) {
					maskStr = patternArray[i].matcher(maskStr).replaceAll(maskArray[i]);
				}
				return maskStr;
			}catch(Exception e) {
				//ignore
			}
		}
		return str;
	}
}
