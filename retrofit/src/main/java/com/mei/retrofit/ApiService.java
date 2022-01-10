package com.mei.retrofit;

import retrofit2.Call;
import retrofit2.http.GET;

/**
 * @author mxb
 * @date 2022/1/5
 * @desc
 * @desired
 */

public interface ApiService {

    /** 请求百度首页 */
    @GET("/")
    public Call<String> baidu();

}
