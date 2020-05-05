package com.dongnao.dnhttp.net2;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by Administrator on 2018/5/2.
 * 连接池
 */
public class ConnectionPool {

    /**
     * 每个连接的检查时间
     * 5s
     * 每隔5s检查连接是否可用
     * 无效则将其从连接池移除
     * <p>
     * 最长闲置时间
     */
    private long keepAlive;

    private boolean cleanupRuning;

    private Deque<HttpConnection> connections = new ArrayDeque<>();

    //清理线程
    private Runnable cleanupRunnable = new Runnable() {
        @Override
        public void run() {
            while (true) {
                //下次检查时间
                long waitDuration = cleanup(System.currentTimeMillis());
                if (waitDuration == -1) {
                    return;
                }
                if (waitDuration > 0) {
                    synchronized (ConnectionPool.this){
                        try {
                            ConnectionPool.this.wait(waitDuration);
                        } catch (InterruptedException e) {
                        }
                    }
                }
            }
        }
    };

    private long cleanup(long now) {
        long longestIdleDuration = -1;
        synchronized (this) {
            Iterator<HttpConnection> iterator = connections.iterator();
            while (iterator.hasNext()) {
                HttpConnection connection = iterator.next();
                //闲置时间 多久没有使用这个HttpConnection了
                long idleDurtation = now - connection.lastUseTime;
                //超过最大允许闲置的时间
                if (idleDurtation > keepAlive) {
                    iterator.remove();
                    connection.close();
                    Log.e("ConnectionPool", "超过闲置时间,移出连接池");
                    continue;
                }
                //记录 最长的 闲置时间
                if (longestIdleDuration < idleDurtation) {
                    longestIdleDuration = idleDurtation;
                }
            }
            // 假如 keepAlive 10s
            // longestIdleDuration 是5s
            if (longestIdleDuration >= 0) {
                return keepAlive - longestIdleDuration;
            }
            //连接池中没有连接
            cleanupRuning = false;
            return longestIdleDuration;
        }

    }

    private static final Executor executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
            60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new ThreadFactory() {
        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread thread = new Thread(r, "Connection Pool");
            //设置为守护线程
            thread.setDaemon(true);
            return thread;
        }
    });

    public ConnectionPool() {
        this(1, TimeUnit.MINUTES);
    }

    public ConnectionPool(long keepAlive, TimeUnit unit) {
        this.keepAlive = unit.toMillis(keepAlive);
    }

    /**
     * 加入连接到连接池
     *
     * @param connection
     */
    public void put(HttpConnection connection) {
        //没有执行清理线程 则执行
        if (!cleanupRuning) {
            cleanupRuning = true;
            executor.execute(cleanupRunnable);
        }
        connections.add(connection);
    }

    /**
     * 获得满足条件可复用的连接池
     *
     * @param host
     * @param port
     * @return
     */
    public synchronized HttpConnection get(String host, int port) {
        Iterator<HttpConnection> iterator = connections.iterator();
        while (iterator.hasNext()) {
            HttpConnection connection = iterator.next();
            //如果查找到连接池始终存在相同host port的连接
            if (connection.isSameAddress(host, port)) {
                iterator.remove();
                return connection;
            }
        }
        return null;
    }

}
