package com.dongnao.dnhttp;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import com.dongnao.dnhttp.net2.Call;
import com.dongnao.dnhttp.net2.Callback;
import com.dongnao.dnhttp.net2.HttpClient;
import com.dongnao.dnhttp.net2.Request;
import com.dongnao.dnhttp.net2.RequestBody;
import com.dongnao.dnhttp.net2.Response;


public class MainActivity extends AppCompatActivity {

    HttpClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        client = new HttpClient.Builder().retrys(3).build();
    }


    public void get(View view) {
        Request request = new Request.Builder()
                .url("http://www.kuaidi100.com/query?type=yuantong&postid=222222222")
                .build();
        Call call = client.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, Throwable throwable) {
                throwable.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) {
                Log.e("响应体", response.getBody());

            }
        });
    }

    public void post(View view) {
        RequestBody body = new RequestBody()
                .add("city", "长沙")
                .add("key", "13cb58f5884f9749287abbead9c658f2");
        Request request = new Request.Builder().url("http://restapi.amap" +
                ".com/v3/weather/weatherInfo").post(body).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, Throwable throwable) {
                throwable.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) {
                Log.e("响应体", response.getBody());
            }
        });
    }
}
