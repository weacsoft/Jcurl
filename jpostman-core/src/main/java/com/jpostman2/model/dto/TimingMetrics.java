package com.jpostman2.model.dto;

/**
 * HTTP 请求性能指标 — 由 TimingEventListener 采集,精确到毫秒。
 * <p>
 * 各阶段含义:
 * <ul>
 *   <li>dnsMs — DNS 解析耗时</li>
 *   <li>tcpMs — TCP 连接耗时</li>
 *   <li>tlsMs — TLS/SSL 握手耗时</li>
 *   <li>ttfbMs — 首字节时间(Time To First Byte),从请求发起到收到响应头首字节</li>
 *   <li>totalMs — 总耗时(从请求发起到响应体读取完成)</li>
 * </ul>
 */
public class TimingMetrics {

    private long dnsMs;
    private long tcpMs;
    private long tlsMs;
    private long ttfbMs;
    private long totalMs;

    public TimingMetrics() {}

    public long getDnsMs() { return dnsMs; }
    public void setDnsMs(long dnsMs) { this.dnsMs = dnsMs; }
    public long getTcpMs() { return tcpMs; }
    public void setTcpMs(long tcpMs) { this.tcpMs = tcpMs; }
    public long getTlsMs() { return tlsMs; }
    public void setTlsMs(long tlsMs) { this.tlsMs = tlsMs; }
    public long getTtfbMs() { return ttfbMs; }
    public void setTtfbMs(long ttfbMs) { this.ttfbMs = ttfbMs; }
    public long getTotalMs() { return totalMs; }
    public void setTotalMs(long totalMs) { this.totalMs = totalMs; }

    @Override
    public String toString() {
        return String.format("DNS=%dms TCP=%dms TLS=%dms TTFB=%dms Total=%dms",
                dnsMs, tcpMs, tlsMs, ttfbMs, totalMs);
    }
}
