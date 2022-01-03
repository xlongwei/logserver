Lajax.logLevel='info';//info warn error off
Lajax.logConsole=false;//是否输出日志到控制台
Lajax.logServer=true;//是否输出日志到logserver
Lajax.token='static';//请求头X-Request-Token表示应用如apidemo
Lajax.prototype._log = function(time, level, ...args){
	if(!Lajax.logLevel 
			|| 'info'==Lajax.logLevel 
			|| ('warn'==Lajax.logLevel && (Lajax.levelEnum.warn==level||Lajax.levelEnum.error==level))
			|| ('error'==Lajax.logLevel && Lajax.levelEnum.error==level)
		){
		if(Lajax.logConsole){
			this._printConsole(time, level, ...args);
		}
		if(Lajax.logServer){
			this._pushToQueue(time, level, ...args);
		}
	}
};
Lajax.prototype._send = function(){
    const logCount = this.queue.length;
    if (logCount) {
        // 如果存在 this.xhr，说明上一次的请求还没有结束，就又准备发送新的请求了，则直接终止上次请求
        if (this.xhr) {
            // 这里必须将上次的回调设为null，否则会打印出请求失败
            this.xhr.onreadystatechange = null;
            this.xhr.abort();
        }

        try {
            this.xhr = new XMLHttpRequest();
            this.xhrOpen.call(this.xhr, 'POST', this.url, true);
            this.xhr.setRequestHeader('Content-Type', 'application/json; charset=utf-8');
            if(Lajax.token){
            	this.xhr.setRequestHeader('X-Request-Token', Lajax.token);
            }
            this.xhrSend.call(this.xhr, JSON.stringify(this.queue));
            this.xhr.onreadystatechange = () => {
                if (this.xhr.readyState === XMLHttpRequest.DONE) {
                    if (this.xhr.status >= 200 && this.xhr.status < 400) {
                        // 日志发送成功，从队列中去除已发送的
                        this.queue.splice(0, logCount);

                        // 重置请求出错次数
                        this.errorReq = 0;

                        // 显示日志发送成功
                        if (console && Lajax.logConsole) {
                            if (this.stylize) {
                                console.log(`%c[${this._getTimeString(null)}] - ${logCount}条日志发送成功！`, `color: ${Lajax.colorEnum.sendSuccess}`);
                            } else {
                                console.log(`${logCount}条日志发送成功！`);
                            }
                        }
                    } else {
                        this._printConsole(null, Lajax.levelEnum.error, `发送日志请求失败！配置的接口地址：${this.url} 状态码：${this.xhr.status}`);
                        this._checkErrorReq();
                    }
                    this.xhr = null;
                }
            };
        } catch (err) {
            this._printConsole(null, Lajax.levelEnum.error, `发送日志请求失败！配置的接口地址：${this.url}`);
            this._checkErrorReq();
            this.xhr = null;
        }
    }
};
var logger = new Lajax({
	url:'lajax',//日志服务器的 URL
	autoLogError:false,//是否自动记录未捕获错误true
	autoLogRejection:false,//是否自动记录Promise错误true
	autoLogAjax:false,//是否自动记录 ajax 请求true
	//logAjaxFilter:function(ajaxUrl, ajaxMethod) {
	//	return false;//ajax 自动记录条件过滤函数true记录false不记录
	//},
	stylize:true,//是否要格式化 console 打印的内容true
	showDesc:false,//是否显示初始化描述信息true
	//customDesc:function(lastUnsend, reqId, idFromServer) {
	//	return 'lajax 前端日志模块加载完成。';
	//},
	interval: 5000,//日志发送到服务端的间隔时间10000毫秒
	maxErrorReq:3 //发送日志请求连续出错的最大次数
});
 