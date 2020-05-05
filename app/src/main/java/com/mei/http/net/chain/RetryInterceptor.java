package com.mei.http.net.chain;

import com.mei.http.net.Call;
import com.mei.http.net.Response;

import android.util.Log;

import java.io.IOException;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 重试拦截器，如果请求失败，负责重新请求，如果需要
 * @desired
 */
public class RetryInterceptor implements Interceptor {

    @Override
    public Response intercept(InterceptorChain chain) throws IOException {
        Log.e("拦截器", "重试拦截器.....");
        Call call = chain.call;
        IOException e = null;
        int retry = call.httpClient().retry();
        for (int i = 0; i < retry + 1; i++) {
            // 请求取消了
            if (call.isCanceled()) {
                throw new IOException("Call canceled");
            }
            try {
                //执行链条中下一个拦截器
                Response response = chain.process();
                return response;
            } catch (IOException ex) {
                ex.printStackTrace();
                e = ex;
            }
        }
        throw e;
    }
}
