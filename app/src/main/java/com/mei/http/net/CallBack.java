package com.mei.http.net;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 请求回调
 * @desired
 */
public interface CallBack {

    void onFailure(Call call, Throwable throwable);

    void onResponse(Call call, Response response);
}
