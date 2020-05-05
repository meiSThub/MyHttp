package com.dongnao.dnhttp.net2;

import com.dongnao.dnhttp.net2.chain.CallServiceInterceptor;
import com.dongnao.dnhttp.net2.chain.ConnectionInterceptor;
import com.dongnao.dnhttp.net2.chain.HeaderInterceptor;
import com.dongnao.dnhttp.net2.chain.Interceptor;
import com.dongnao.dnhttp.net2.chain.InterceptorChain;
import com.dongnao.dnhttp.net2.chain.RetryInterceptor;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by Administrator on 2018/4/27.
 */

public class Call {

    //请求
    Request request;
    //
    HttpClient client;
    //是否执行过
    boolean executed;
    //是否取消
    private boolean canceled;

    public Call(Request request, HttpClient client) {
        this.request = request;
        this.client = client;
    }

    public Request request() {
        return request;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void enqueue(Callback callback) {
        synchronized (this) {
            if (executed) {
                throw new IllegalStateException("已经执行过了。");
            }
            executed = true;
        }
        //交给调度器调度
        client.dispatcher().enqueue(new AsyncCall(callback));
    }

    public HttpClient client() {
        return client;
    }

    /**
     * 取消
     */
    public void cancel() {
        canceled = true;
    }

    /**
     * 执行网络请求的线程
     */
    class AsyncCall implements Runnable {

        private Callback callback;

        public AsyncCall(Callback callback) {
            this.callback = callback;
        }

        @Override
        public void run() {
            //信号 是否回调过
            boolean signalledCallbacked = false;
            try {
                Response response = getResponse();
                if (canceled) {
                    signalledCallbacked = true;
                    callback.onFailure(Call.this, new IOException("Canceled"));
                } else {
                    signalledCallbacked = true;
                    callback.onResponse(Call.this, response);
                }
            } catch (Exception e) {
                if (!signalledCallbacked) {
                    callback.onFailure(Call.this, e);
                }
            } finally {
                //将这个任务从调度器移除
                client.dispatcher().finished(this);
            }

        }

        public String host() {
            return request.url().getHost();
        }


    }

    public Response getResponse() throws Exception {
        ArrayList<Interceptor> interceptors = new ArrayList<>();
        //重试拦截器
        interceptors.add(new RetryInterceptor());
        //请求头拦截器
        interceptors.add(new HeaderInterceptor());
        //连接拦截器
        interceptors.add(new ConnectionInterceptor());
        //通信拦截器
        interceptors.add(new CallServiceInterceptor());
        InterceptorChain chain = new InterceptorChain(interceptors, 0, this, null);
        return chain.process();
    }
}
