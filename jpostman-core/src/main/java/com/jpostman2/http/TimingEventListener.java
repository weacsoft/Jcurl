package com.jpostman2.http;

import com.jpostman2.model.dto.TimingMetrics;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Protocol;
import okhttp3.Response;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;

/**
 * OkHttp 事件监听器 — 采集 HTTP 请求各阶段耗时(DNS/TCP/TLS/TTFB/Total)。
 * <p>
 * 每个 Call 创建独立实例(通过 EventListener.Factory),避免多请求并发时指标串扰。
 * 采集结果在 {@link #getMetrics()} 中获取。
 */
public class TimingEventListener extends EventListener {

    private final TimingMetrics metrics = new TimingMetrics();

    // 各阶段开始时间戳(纳秒)
    private long callStartNanos;
    private long dnsStartNanos;
    private long connectStartNanos;
    private long secureStartNanos;

    @Override
    public void callStart(Call call) {
        callStartNanos = System.nanoTime();
    }

    @Override
    public void dnsStart(Call call, String domainName) {
        dnsStartNanos = System.nanoTime();
    }

    @Override
    public void dnsEnd(Call call, String domainName, List<InetAddress> inetAddressList) {
        if (dnsStartNanos > 0) {
            metrics.setDnsMs(elapsedMs(dnsStartNanos));
        }
    }

    @Override
    public void connectStart(Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
        connectStartNanos = System.nanoTime();
    }

    @Override
    public void connectEnd(Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol) {
        if (connectStartNanos > 0) {
            metrics.setTcpMs(elapsedMs(connectStartNanos));
        }
    }

    @Override
    public void connectFailed(Call call, InetSocketAddress inetSocketAddress, Proxy proxy,
                              Protocol protocol, IOException ioe) {
        // 连接失败不更新 TCP 指标
    }

    @Override
    public void secureConnectStart(Call call) {
        secureStartNanos = System.nanoTime();
    }

    @Override
    public void secureConnectEnd(Call call, Handshake handshake) {
        if (secureStartNanos > 0) {
            metrics.setTlsMs(elapsedMs(secureStartNanos));
        }
    }

    @Override
    public void responseHeadersStart(Call call) {
        // TTFB = 从请求发起到收到响应头首字节
        if (callStartNanos > 0) {
            metrics.setTtfbMs(elapsedMs(callStartNanos));
        }
    }

    @Override
    public void callEnd(Call call) {
        if (callStartNanos > 0) {
            metrics.setTotalMs(elapsedMs(callStartNanos));
        }
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
        if (callStartNanos > 0) {
            metrics.setTotalMs(elapsedMs(callStartNanos));
        }
    }

    /** 获取采集到的性能指标 */
    public TimingMetrics getMetrics() {
        return metrics;
    }

    /** 计算从指定时间戳到现在的毫秒数 */
    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
