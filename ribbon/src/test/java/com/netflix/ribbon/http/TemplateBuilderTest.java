/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.ribbon.http;

import static org.junit.Assert.assertEquals;

import com.netflix.ribbon.hystrix.HystrixObservableCommandChain;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.reactivex.netty.protocol.http.client.HttpClientRequest;
import io.reactivex.netty.protocol.http.client.HttpRequestHeaders;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import rx.Observable;

import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.ribbon.CacheProvider;
import com.netflix.ribbon.ClientOptions;
import com.netflix.ribbon.Ribbon;
import com.netflix.ribbon.RibbonRequest;

public class TemplateBuilderTest {
    
    private static class FakeCacheProvider implements CacheProvider<ByteBuf> {
        String id;
        
        FakeCacheProvider(String id) {
            this.id = id;
        }
        
        @Override
        public Observable<ByteBuf> get(final String key,
                Map<String, Object> requestProperties) {
            if (key.equals(id)) {
                return Observable.just(Unpooled.buffer().writeBytes(id.getBytes(Charset.defaultCharset())));

            } else {
                return Observable.error(new IllegalArgumentException());
            }
        };
    }        

    @Test
    public void testVarReplacement() {
        HttpResourceGroup group = Ribbon.createHttpResourceGroup("test");
        
        HttpRequestTemplate<ByteBuf> template = group.newRequestTemplate("testVarReplacement", ByteBuf.class);
        template.withUriTemplate("/foo/{id}?name={name}");
        HttpClientRequest<ByteBuf> request = template
                .withMethod("GET")
                .requestBuilder()
                .withRequestProperty("id", "3")
                .withRequestProperty("name", "netflix")
                .createClientRequest();
        assertEquals("/foo/3?name=netflix", request.getUri());
    }
    
    @Test
    public void testCacheKeyTemplates() {
        HttpResourceGroup group = Ribbon.createHttpResourceGroup("test");
        
        HttpRequestTemplate<ByteBuf> template = group.newRequestTemplate("testCacheKeyTemplates", ByteBuf.class);
        template.withUriTemplate("/foo/{id}")
                .withMethod("GET")
            .withCacheProvider("/cache/{id}", new FakeCacheProvider("/cache/5"));
        
        RibbonRequest<ByteBuf> request = template.requestBuilder().withRequestProperty("id", 5).build();
        ByteBuf result = request.execute();
        assertEquals("/cache/5", result.toString(Charset.defaultCharset()));
    }
    
    @Test
    public void testHttpHeaders() {
        HttpResourceGroup group = Ribbon.createHttpResourceGroup("test");
        group.withCommonHeader("header1", "group");
        
        HttpRequestTemplate<String> template = group.newRequestTemplate("testHttpHeaders", String.class);
        template.withUriTemplate("/foo/bar")
                .withMethod("GET")
            .withHeader("header2", "template")
            .withHeader("header1", "template");
        
        HttpClientRequest<ByteBuf> request = template.requestBuilder().createClientRequest();
        HttpRequestHeaders headers = request.getHeaders();
        List<String> header1 = headers.getAll("header1");
        assertEquals(2, header1.size());
        assertEquals("group", header1.get(0));
        assertEquals("template", header1.get(1));
        List<String> header2 = headers.getAll("header2");
        assertEquals(1, header2.size());
        assertEquals("template", header2.get(0));
    }
    
    @Test
    public void testHystrixProperties() {
        ClientOptions clientOptions = ClientOptions.create()
                .withMaxAutoRetriesNextServer(1)
                .withMaxAutoRetries(1)
                .withConnectTimeout(1000)
                .withMaxTotalConnections(400)
                .withReadTimeout(2000);
        HttpResourceGroup group = Ribbon.createHttpResourceGroup("test", clientOptions);
        HttpRequestTemplate<ByteBuf> template = group.newRequestTemplate("testHystrixProperties", ByteBuf.class);
        HttpRequest<ByteBuf> request = (HttpRequest<ByteBuf>) template.withMethod("GET")
                .withMethod("GET")
            .withUriTemplate("/foo/bar")
            .requestBuilder().build();
        HystrixObservableCommandChain<ByteBuf> hystrixCommandChain = request.createHystrixCommandChain();
        HystrixCommandProperties props = hystrixCommandChain.getCommands().get(0).getProperties();
        assertEquals(400, props.executionIsolationSemaphoreMaxConcurrentRequests().get().intValue());
        assertEquals(12000, props.executionIsolationThreadTimeoutInMilliseconds().get().intValue());
    }
}

