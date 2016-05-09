package io.openio.sds.proxy;

import io.openio.sds.http.OioHttpSettings;
import io.openio.sds.pool.PoolingSettings;

/**
 * 
 * @author Christopher Dedeurwaerder
 *
 */
public class ProxySettings {

    private String url;
    private String ns;
    private OioHttpSettings http = new OioHttpSettings();
    private PoolingSettings pooling = new PoolingSettings();

    public ProxySettings() {
    }

    public String url() {
        return url;
    }

    public ProxySettings url(String url) {
        this.url = url;
        return this;
    }

    public String ns() {
        return ns;
    }

    public ProxySettings ns(String ns) {
        this.ns = ns;
        return this;
    }

    public OioHttpSettings http() {
        return http;
    }

    public ProxySettings http(OioHttpSettings http) {
        this.http = http;
        return this;
    }

    public PoolingSettings pooling() {
        return pooling;
    }

    public ProxySettings pooling(PoolingSettings pooling) {
        this.pooling = pooling;
        return this;
    }
}