package com.dongnao.dnhttp.net.chain;

import android.util.Log;

import com.dongnao.dnhttp.net.Call;
import com.dongnao.dnhttp.net.Request;
import com.dongnao.dnhttp.net.Response;

import java.io.IOException;

/**
 * @author Lance
 * @date 2018/4/17
 */

public class RetryInterceptor implements Interceptor {


    @Override
    public Response intercept(InterceptorChain chain) throws IOException {
        Log.e("interceprot", "重试拦截器....");
        Call call = chain.call;
        IOException exception = null;
        for (int i = 0; i < chain.call.client().retrys(); i++) {
            if (call.isCanceled()) {
                throw new IOException("Canceled");
            }
            try {
                Response response = chain.proceed();
                return response;
            } catch (IOException e) {
                exception = e;
            }
        }
        throw exception;
    }
}
