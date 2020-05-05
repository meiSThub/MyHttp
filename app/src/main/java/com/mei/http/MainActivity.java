package com.mei.http;

import com.mei.http.net.Call;
import com.mei.http.net.CallBack;
import com.mei.http.net.HttpClient;
import com.mei.http.net.Request;
import com.mei.http.net.RequestBody;
import com.mei.http.net.Response;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

public class MainActivity extends AppCompatActivity {

    HttpClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        client = new HttpClient.Builder().retry(3).build();
    }

    public void get(View view) throws IllegalAccessException {
        Request request = new Request.Builder()
                .url("http://www.kuaidi100.com/query?type=yuantong&postid=222222222")
                .build();
        Call call = client.newCall(request);
        call.enqueue(new CallBack() {
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

    public void post(View view) throws IllegalAccessException {
        RequestBody body = new RequestBody()
                .add("city", "长沙")
                .add("key", "13cb58f5884f9749287abbead9c658f2");
        Request request = new Request.Builder().url("http://restapi.amap" +
                ".com/v3/weather/weatherInfo").post(body).build();
        client.newCall(request).enqueue(new CallBack() {
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
