package com.mei.retrofit

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.mei.retrofit.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.*
import java.io.IOException
import java.lang.reflect.Type

class MainActivity : AppCompatActivity() {

    private val mBinding: ActivityMainBinding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(mBinding.root)
    }

    fun baidu(view: android.view.View) {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://www.baidu.com")
            .addConverterFactory(object : Converter.Factory() {
                override fun responseBodyConverter(
                    type: Type,
                    annotations: Array<out Annotation>,
                    retrofit: Retrofit
                ): Converter<ResponseBody, *> {
                    return object : Converter<ResponseBody, String> {
                        override fun convert(value: ResponseBody): String? {
                            return value.string()
                        }
                    }
                }
            }).build()
        val service = retrofit.create(ApiService::class.java)

        val call: Call<String> = service.baidu()
        call.enqueue(object : Callback<String> {
            override fun onResponse(call: Call<String>, response: Response<String>) {
                println("当前线程：${Thread.currentThread()}")
                println("请求成功：${response.body()}")
                mBinding.tvMsg.text = response.body()
            }

            override fun onFailure(call: Call<String>, t: Throwable) {
                println("请求失败")
                t.printStackTrace()
            }
        })
    }
}