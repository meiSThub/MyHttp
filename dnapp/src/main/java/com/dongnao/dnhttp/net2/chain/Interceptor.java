package com.dongnao.dnhttp.net2.chain;


import com.dongnao.dnhttp.net2.Response;

import java.io.IOException;

/**
 * @author Lance
 * @date 2018/4/17
 */

public interface Interceptor {

    Response intercept(InterceptorChain chain) throws IOException;
}
