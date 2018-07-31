/**
 * ShinyProxy
 *
 * Copyright (C) 2016-2018 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import javax.inject.Inject;

import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory;
import org.springframework.boot.context.embedded.PortInUseException;
import org.springframework.boot.context.embedded.undertow.UndertowDeploymentInfoCustomizer;
import org.springframework.boot.context.embedded.undertow.UndertowEmbeddedServletContainerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSinkChannel;

import eu.openanalytics.shinyproxy.services.ProxyService;
import eu.openanalytics.shinyproxy.services.ProxyService.MappingListener;
import eu.openanalytics.shinyproxy.util.Utils;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.client.UndertowClient;
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.RequestDumpingHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.PathMatcher;
import io.undertow.util.StringReadChannelListener;

@SpringBootApplication
@EnableAsync
@Configuration
public class ShinyProxyApplication {

	private static final Logger log = Utils.loggerForThisClass();
	private static final OptionMap DEFAULT_OPTIONS;
	
	@Inject
	ProxyService proxyService;

	@Inject
	Environment environment;

	public static void main(String[] args) {
		log.info("Started");
		SpringApplication app = new SpringApplication(ShinyProxyApplication.class);

		boolean hasExternalConfig = Files.exists(Paths.get("application.yml"));
		if (!hasExternalConfig) app.setAdditionalProfiles("demo");

		try {
			app.run(args);
		} catch (Exception e) {
			// Workaround for bug in UndertowEmbeddedServletContainer.start():
			// If undertow.start() fails, started remains false which prevents undertow.stop() from ever being called.
			// Undertow's (non-daemon) XNIO worker threads will then prevent the JVM from exiting.
			if (e instanceof PortInUseException) System.exit(-1);
		}
		log.info("Finished");
	}

	public static String getContextPath(Environment env) {
		String contextPath = env.getProperty("server.contextPath");
		if (contextPath == null) contextPath = "";
		return contextPath;
	}

	@Bean
	public EmbeddedServletContainerFactory servletContainer() {
		UndertowEmbeddedServletContainerFactory factory = new UndertowEmbeddedServletContainerFactory();
		factory.addDeploymentInfoCustomizers(new UndertowDeploymentInfoCustomizer() {
			@Override
			public void customize(DeploymentInfo deploymentInfo) {
				deploymentInfo.addInitialHandlerChainWrapper(new RootHandlerWrapper());
			}
		});
		factory.setPort(Integer.parseInt(environment.getProperty("shiny.proxy.port", "8080")));
		return factory;	
	}
	
	static {
        final OptionMap.Builder builder = OptionMap.builder()
                .set(Options.WORKER_IO_THREADS, 8)
                .set(Options.TCP_NODELAY, true)
                .set(Options.KEEP_ALIVE, true)
                .set(Options.WORKER_NAME, "Client");

        DEFAULT_OPTIONS = builder.getMap();
    }

	private class RootHandlerWrapper implements HandlerWrapper {		
		
		public HttpHandler wrap(HttpHandler defaultHandler) {
			PathHandler pathHandler = new PathHandler(defaultHandler) {
				
				@SuppressWarnings("unchecked")
				@Override
				public void handleRequest(HttpServerExchange exchange) throws Exception {
					/*ClientConnection connection = null;
					String url = "http://192.168.233.128:3838/app/hello";
					if (exchange.getRequestURI().endsWith("/app/hello")) {
			
						// Create an instance of HttpClient.
					    HttpClient client = new HttpClient();

					    // Create a method instance.
					    GetMethod method = new GetMethod(url);
					    
					    // Provide custom retry handler is necessary
					    method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, 
					    		new DefaultHttpMethodRetryHandler(3, false));
					    
					    ByteBuffer buf = null;

					    try {
					      // Execute the method.
					      int statusCode = client.executeMethod(method);

					      if (statusCode != HttpStatus.SC_OK) {
					        System.err.println("Method failed: " + method.getStatusLine());
					      }

					      // Read the response body.
					      byte[] responseBody = method.getResponseBody();
					      buf = ByteBuffer.wrap(responseBody);
					      // Deal with the response.
					      // Use caution: ensure correct character encoding and is not binary data
					      System.out.println(new String(responseBody));

					    } catch (HttpException e) {
					      System.err.println("Fatal protocol violation: " + e.getMessage());
					      e.printStackTrace();
					    } catch (IOException e) {
					      System.err.println("Fatal transport error: " + e.getMessage());
					      e.printStackTrace();
					    } finally {
					      // Release the connection.
					      method.releaseConnection();
					    }  
  
						    
						
						//exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");

                        exchange.getResponseSender().send(buf);						
					}
					else super.handleRequest(exchange);*/
					
					log.info("handleRequest started: " + exchange.getRequestURI());
					Field field = PathHandler.class.getDeclaredField("pathMatcher");
					field.setAccessible(true);
					PathMatcher<HttpHandler> pathMatcher = (PathMatcher<HttpHandler>) field.get(this);
					PathMatcher.PathMatch<HttpHandler> match = pathMatcher.match(exchange.getRelativePath());

					// Proxy URLs bypass the Spring security filters, so the session ID must be checked here instead.
					boolean sessionMatch = true;
					if (match.getValue() instanceof ProxyHandler) {
						//sessionMatch = proxyService.sessionOwnsProxy(exchange);
					}
					log.info("handleRequest sessionMatch: " +  ((Boolean)sessionMatch).toString());

					if (sessionMatch) {
						
						super.handleRequest(exchange);
					} else {
						exchange.setStatusCode(401);
						exchange.getResponseChannel().write(ByteBuffer.wrap("No session ID found".getBytes()));
					}
					log.info("handleRequest finished: " + exchange.getRequestURI());					
				}
			};
			
			LoadBalancingProxyClient proxyClient1 = new LoadBalancingProxyClient();
			LoadBalancingProxyClient proxyClient2 = new LoadBalancingProxyClient();
			
			try {
				proxyClient1.addHost(new URI("http://192.168.233.128:3838/app/hello"));
				proxyClient2.addHost(new URI("http://192.168.233.128:3838/app/rmd"));
			} catch (URISyntaxException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			ProxyHandler proxyHandler1 = new ProxyHandler(proxyClient1, ResponseCodeHandler.HANDLE_404);
			ProxyHandler proxyHandler2 = new ProxyHandler(proxyClient2, ResponseCodeHandler.HANDLE_404);
			
			pathHandler.addPrefixPath("/hello", proxyHandler1);
			pathHandler.addPrefixPath("/rmd", proxyHandler2);			
			
			RequestDumpingHandler debugHandler = new RequestDumpingHandler(pathHandler);
			
			proxyService.addMappingListener(new MappingListener() {
				@Override
				public void mappingAdded(String mapping, URI target) {
					/*log.info("mappingAdded started : mapping=" + mapping+ ", target="+ target);
					LoadBalancingProxyClient proxyClient = new LoadBalancingProxyClient();
					proxyClient.addHost(target);
					HttpHandler handler = new ProxyHandler(proxyClient, ResponseCodeHandler.HANDLE_404);
					pathHandler.addExactPath("/app/hello", handler);
					//pathHandler.addPrefixPath("/app", handler);
					log.info("mappingAdded finished : mapping=" + mapping+ ", target="+ target);*/
				}
				@Override
				public void mappingRemoved(String mapping) {
					/*log.info("mappingRemoved started : mapping=" + mapping);
					pathHandler.removePrefixPath(mapping);
					log.info("mappingRemoved finished : mapping=" + mapping);*/
				}
			});
						
			return debugHandler;
		}
	}	
}
