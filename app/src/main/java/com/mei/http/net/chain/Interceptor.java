package com.mei.http.net.chain;

import com.mei.http.net.Response;

import java.io.IOException;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 拦截器
 * @desired
 */
public interface Interceptor {

    /**
     * 拦截请求，增加自己的判断与处理
     */
    Response intercept(InterceptorChain chain) throws IOException;
}
