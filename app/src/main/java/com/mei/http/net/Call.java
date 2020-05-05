package com.mei.http.net;

import com.mei.http.R;
import com.mei.http.net.chain.CallServiceInterceptor;
import com.mei.http.net.chain.ConnectionInterceptor;
import com.mei.http.net.chain.HeaderInterceptor;
import com.mei.http.net.chain.Interceptor;
import com.mei.http.net.chain.InterceptorChain;
import com.mei.http.net.chain.RetryInterceptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 请求执行者
 * @desired
 */
public class Call {

    private Request request;

    private HttpClient httpClient;

    // 请求是否执行过
    private boolean executed;

    // 请求是否取消
    private boolean canceled;


    public Call(Request request, HttpClient httpClient) {
        this.request = request;
        this.httpClient = httpClient;
    }

    public Request request() {
        return request;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void enqueue(CallBack callBack) throws IllegalAccessException {
        synchronized (this) {
            if (executed) {
                throw new IllegalAccessException("请求已经执行过连。。");
            }
            executed = true;
            httpClient.dispatcher().enqueue(new AsyncCall(callBack));
        }
    }


    public class AsyncCall implements Runnable {

        CallBack callBack;

        public AsyncCall(CallBack callBack) {
            this.callBack = callBack;
        }

        @Override
        public void run() {
            // 是否回调过
            boolean isCallBack = false;

            try {
                Response response = getResponse();

                if (canceled) {
                    callBack.onFailure(Call.this, new IOException("Canceled"));
                    isCallBack = true;
                } else {
                    callBack.onResponse(Call.this, response);
                    isCallBack = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (!isCallBack) {
                    callBack.onFailure(Call.this, e);
                }
            } finally {
                // 最后，通知调度器，请求完成
                httpClient.dispatcher().finished(this);
            }
        }

        public String host() {
            return request.url().getHost();
        }
    }

    private Response getResponse() throws IOException {
        List<Interceptor> interceptors = new ArrayList<>();
        //重试拦截器
        interceptors.add(new RetryInterceptor());
        //请求头拦截器
        interceptors.add(new HeaderInterceptor());
        //连接拦截器
        interceptors.add(new ConnectionInterceptor());
        //通信拦截器
        interceptors.add(new CallServiceInterceptor());

        // 把拦截器，封装成责任链
        InterceptorChain chain = new InterceptorChain(interceptors, 0, this, null);
        return chain.process();//
    }

}
