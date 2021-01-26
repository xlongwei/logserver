package com.xlongwei.logserver;

import com.networknt.server.IConfigLoader;

/**
 * DefaultConfigLoader依赖Http2Client会多开线程，通过startup.yml指定此实现，默认从config加载配置即可
 * @author xlongwei
 *
 */
public class DummyConfigLoader implements IConfigLoader {

	@Override
	public void init() {

	}

}
