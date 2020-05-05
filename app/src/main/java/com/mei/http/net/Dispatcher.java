package com.mei.http.net;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 网络请求任务调度器
 * @desired
 */
public class Dispatcher {

    private int maxRequests = 64;// 最大请求数

    // 相同host的最大请求数，同时请求的相同的host的最大数
    private int maxRequestsPreHost = 5;

    // 正在执行的任务队列
    private Deque<Call.AsyncCall> runningAsyncCalls = new ArrayDeque<>();

    // 等待执行的任务队列
    private Deque<Call.AsyncCall> readyAsyncCalls = new ArrayDeque<>();

    // 线程池服务
    private ExecutorService executorService;

    public Dispatcher() {
        this(64, 5);
    }

    public Dispatcher(int maxRequests, int maxRequestsPreHost) {
        this.maxRequests = maxRequests;
        this.maxRequestsPreHost = maxRequestsPreHost;
    }

    // 把线程任务交给线程池执行
    public void enqueue(Call.AsyncCall asyncCall) {
        //不能超过最大请求数与相同host的请求数
        //满足条件意味着可以马上开始任务
        if (runningAsyncCalls.size() < maxRequests
                && getRunningCallsForHost(asyncCall) < maxRequestsPreHost) {
            runningAsyncCalls.add(asyncCall);// 加入到执行队列
            executorService().execute(asyncCall);
        } else {// 否则，加入到等待队列
            readyAsyncCalls.add(asyncCall);
        }
    }

    /**
     * 创建线程池
     */
    private synchronized ExecutorService executorService() {
        if (null == executorService) {
            executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 1, TimeUnit.MINUTES,
                    new SynchronousQueue<Runnable>(), new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "http client");
                    return thread;
                }
            });
        }
        return executorService;
    }

    /**
     * 获取与当前请求相同host的请求数量
     *
     * @param asyncCall 当前请求
     */
    private int getRunningCallsForHost(Call.AsyncCall asyncCall) {
        int size = 0;
        for (Call.AsyncCall call : runningAsyncCalls) {
            if (call.host().equals(asyncCall.host())) {
                size++;
            }
        }
        return size;
    }


    /**
     * 任务执行完成，1.从执行队列移除 2. 把等待队列中的请求加入到执行队列中去请求
     */
    public void finished(Call.AsyncCall asyncCall) {

        synchronized (this) {
            // 1.从执行队列移除任务
            runningAsyncCalls.remove(asyncCall);

            // 2.把等待队列中的请求加入到执行队列中去请求
            checkReady();
        }
    }

    private void checkReady() {
        // 执行队列已满，达到了同时请求最大数
        if (runningAsyncCalls.size() > maxRequests) {
            return;
        }

        // 没有等待执行到任务
        if (readyAsyncCalls.isEmpty()) {
            return;
        }
        Iterator<Call.AsyncCall> iterator = readyAsyncCalls.iterator();
        while (iterator.hasNext()) {
            //获得一个等待执行的任务
            Call.AsyncCall next = iterator.next();
            //如果获得的等待执行的任务 执行后 小于host相同最大允许数 就可以去执行
            if (runningAsyncCalls.size() < maxRequests
                    && getRunningCallsForHost(next) < maxRequestsPreHost) {
                iterator.remove();
                runningAsyncCalls.add(next);
                executorService().execute(next);
            }
            //如果正在执行的任务达到了最大
            if (runningAsyncCalls.size() >= maxRequests) {
                return;
            }
        }

    }


}
