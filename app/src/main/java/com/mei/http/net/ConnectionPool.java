package com.mei.http.net;

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
 * @author mxb
 * @date 2020/5/4
 * @desc 请求连接池
 * @desired
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

    private Deque<HttpConnection> mConnections = new ArrayDeque<>();

    // 清理无效连接任务是否正在执行
    private boolean cleanupRunning;

    public ConnectionPool() {
        // 每隔1分钟，检查一次
        this(1, TimeUnit.MINUTES);
    }

    public ConnectionPool(int keepAlive, TimeUnit timeUnit) {
        this.keepAlive = timeUnit.toMillis(keepAlive);
    }


    // 线程池
    private Executor mExecutor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "Connection Pool clean ");
            //设置为守护线程,如果app进程被销毁，则该线程也被销毁
            thread.setDaemon(true);
            return thread;
        }
    });

    // 清理无效连接的线程
    private Runnable cleanupRunnable = new Runnable() {
        @Override
        public void run() {
            // 下次检查时间
            long waitDuration = cleanup(System.currentTimeMillis());
            if (waitDuration == -1) {// 说明不需要清理了
                return;
            }

            if (waitDuration > 0) {
                synchronized (ConnectionPool.this) {
                    try {
                        // 等待指定的时间之后，会继续执行该线程
                        ConnectionPool.this.wait(waitDuration);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    };

    /**
     * 清理无效的与服务器的连接
     */
    private long cleanup(long now) {
        long longestIdleDuration = -1;

        synchronized (this) {
            Iterator<HttpConnection> iterator = mConnections.iterator();
            while (iterator.hasNext()) {
                HttpConnection connection = iterator.next();
                //闲置时间 多久没有使用这个HttpConnection了
                long idleDuration = now - connection.lastUseTime;
                // 超过最大允许闲置的时间
                if (idleDuration > keepAlive) {
                    iterator.remove();
                    connection.close();
                    Log.e("ConnectionPool", "超过闲置时间,移出连接池");
                    continue;
                }

                // 记录最长闲置时间
                if (longestIdleDuration < idleDuration) {
                    longestIdleDuration = idleDuration;
                }
            }

            // 假如 keepAlive 10s
            // longestIdleDuration 是5s
            if (longestIdleDuration > 0) {
                return keepAlive - longestIdleDuration;
            }
            //连接池中没有连接
            cleanupRunning = false;
            return longestIdleDuration;
        }
    }

    /**
     * 加入连接到连接池
     *
     * @param connection 客户端与服务器的连接
     */
    public void put(HttpConnection connection) {
        if (!cleanupRunning) {
            cleanupRunning = true;
            mExecutor.execute(cleanupRunnable);
        }

        mConnections.add(connection);
    }

    /**
     * 从连接池中，获取一个http连接，即获得满足条件可复用的连接池
     *
     * @param host 服务器地址
     * @param port 端口号
     */
    public HttpConnection get(String host, int port) {
        Iterator<HttpConnection> iterator = mConnections.iterator();
        while (iterator.hasNext()) {
            HttpConnection connection = iterator.next();
            if (connection.isSameAddress(host, port)) {
                iterator.remove();
                return connection;
            }
        }
        return null;
    }
}
